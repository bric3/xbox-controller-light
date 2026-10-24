// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

#include <Adafruit_TinyUSB.h>
#include <bluefruit.h>
#include <stdarg.h>

static volatile uint16_t connection = BLE_CONN_HANDLE_INVALID;
static volatile uint32_t notifications;
static volatile uint16_t pendingWriteCommand;
static SemaphoreHandle_t outputLock;
static char input[600];
static size_t inputSize;
static bool overflow;

static void line(const char* format, ...) {
  char text[1200];
  va_list args;
  va_start(args, format);
  vsnprintf(text, sizeof(text), format, args);
  va_end(args);
  xSemaphoreTake(outputLock, portMAX_DELAY);
  Serial.println(text);
  xSemaphoreGive(outputLock);
}

static void hex(const uint8_t* bytes, size_t size, char* result) {
  for (size_t i = 0; i < size; i++) sprintf(result + 2 * i, "%02x", bytes[i]);
  result[2 * size] = 0;
}

static bool unhex(const char* text, uint8_t* bytes, size_t capacity, size_t& size) {
  size_t length = strlen(text);
  if (length % 2 || length / 2 > capacity) return false;
  size = length / 2;
  for (size_t i = 0; i < size; i++) {
    char pair[] = {text[2 * i], text[2 * i + 1], 0};
    char* end;
    unsigned long value = strtoul(pair, &end, 16);
    if (end != pair + 2 || !isxdigit(pair[0]) || !isxdigit(pair[1])) return false;
    bytes[i] = value;
  }
  return true;
}

static void uuidText(ble_uuid_t uuid, char* result) {
  if (uuid.type == BLE_UUID_TYPE_BLE) {
    sprintf(result, "%04x", uuid.uuid);
    return;
  }
  uint8_t size = 16, bytes[16];
  if (sd_ble_uuid_encode(&uuid, &size, bytes) != NRF_SUCCESS) {
    strcpy(result, "unknown");
    return;
  }
  for (uint8_t i = 0; i < 8; i++) {
    uint8_t swap = bytes[i]; bytes[i] = bytes[15 - i]; bytes[15 - i] = swap;
  }
  hex(bytes, size, result);
}

static void scan(ble_gap_evt_adv_report_t* report) {
  uint8_t name[40] = {};
  uint8_t size = Bluefruit.Scanner.parseReportByType(report, BLE_GAP_AD_TYPE_COMPLETE_LOCAL_NAME, name, sizeof(name) - 1);
  if (!size) size = Bluefruit.Scanner.parseReportByType(report, BLE_GAP_AD_TYPE_SHORT_LOCAL_NAME, name, sizeof(name) - 1);
  name[min(size, (uint8_t)39)] = 0;
  if (strstr((char*)name, "Xbox") || strstr((char*)name, "XBOX")) {
    char address[13];
    hex(report->peer_addr.addr, 6, address);
    line("ADV %s %u %d %s", address, report->peer_addr.addr_type, report->rssi, name);
  }
  Bluefruit.Scanner.resume();
}

static void connected(uint16_t handle) {
  connection = handle;
  notifications = 0;
  line("CONNECTED %u", handle);
}

static void disconnected(uint16_t handle, uint8_t reason) {
  connection = BLE_CONN_HANDLE_INVALID;
  pendingWriteCommand = 0;
  line("DISCONNECTED %u %02x", handle, reason);
}

static void event(ble_evt_t* evt) {
  uint16_t id = evt->header.evt_id;
  if (id == BLE_GAP_EVT_AUTH_STATUS) {
    line("AUTH %02x", evt->evt.gap_evt.params.auth_status.auth_status);
    return;
  }
  if (id == BLE_GAP_EVT_CONN_SEC_UPDATE) {
    auto security = evt->evt.gap_evt.params.conn_sec_update.conn_sec;
    line("SECURITY %u %u %u", security.sec_mode.sm, security.sec_mode.lv, security.encr_key_size);
    return;
  }
  if (id < BLE_GATTC_EVT_BASE || id > BLE_GATTC_EVT_LAST) return;
  auto& gatt = evt->evt.gattc_evt;
  switch (id) {
    case BLE_GATTC_EVT_PRIM_SRVC_DISC_RSP:
      for (uint16_t i = 0; i < gatt.params.prim_srvc_disc_rsp.count; i++) {
        auto& service = gatt.params.prim_srvc_disc_rsp.services[i];
        char uuid[40]; uuidText(service.uuid, uuid);
        line("SERVICE %04x %04x %s", service.handle_range.start_handle, service.handle_range.end_handle, uuid);
      }
      line("DONE SERVICES %04x", gatt.gatt_status);
      break;
    case BLE_GATTC_EVT_CHAR_DISC_RSP:
      for (uint16_t i = 0; i < gatt.params.char_disc_rsp.count; i++) {
        auto& chr = gatt.params.char_disc_rsp.chars[i];
        char uuid[40]; uuidText(chr.uuid, uuid);
        const auto& p = chr.char_props;
        uint8_t properties = p.broadcast | (p.read << 1) | (p.write_wo_resp << 2) | (p.write << 3)
            | (p.notify << 4) | (p.indicate << 5) | (p.auth_signed_wr << 6) | (chr.char_ext_props << 7);
        line("CHAR %04x %04x %02x %s", chr.handle_decl, chr.handle_value, properties, uuid);
      }
      line("DONE CHARS %04x", gatt.gatt_status);
      break;
    case BLE_GATTC_EVT_DESC_DISC_RSP:
      for (uint16_t i = 0; i < gatt.params.desc_disc_rsp.count; i++) {
        auto& desc = gatt.params.desc_disc_rsp.descs[i];
        char uuid[40]; uuidText(desc.uuid, uuid);
        line("DESC %04x %s", desc.handle, uuid);
      }
      line("DONE DESCS %04x", gatt.gatt_status);
      break;
    case BLE_GATTC_EVT_READ_RSP: {
      auto& read = gatt.params.read_rsp;
      char data[1025]; hex(read.data, min(read.len, (uint16_t)512), data);
      line("READ %04x %04x %04x %s", gatt.gatt_status, read.handle, read.offset, data);
      break;
    }
    case BLE_GATTC_EVT_WRITE_RSP:
      line("WRITE %04x %04x", gatt.gatt_status, gatt.params.write_rsp.handle);
      break;
    case BLE_GATTC_EVT_WRITE_CMD_TX_COMPLETE:
      if (pendingWriteCommand) {
        line("SENT %04x %u", pendingWriteCommand, gatt.params.write_cmd_tx_complete.count);
        pendingWriteCommand = 0;
      }
      break;
    case BLE_GATTC_EVT_HVX: {
      auto& value = gatt.params.hvx;
      notifications++;
      // Keep AFK sessions bounded while STATUS retains the notification count.
      if (notifications > 8) break;
      char data[1025]; hex(value.data, min(value.len, (uint16_t)512), data);
      line("NOTIFY %04x %s", value.handle, data);
      break;
    }
    case BLE_GATTC_EVT_TIMEOUT:
      line("ERROR GATT_TIMEOUT");
      Bluefruit.disconnect(connection);
      break;
  }
}

static bool handleNumber(const char* text, uint16_t& value, bool allowZero = false) {
  if (!text || !*text) return false;
  char* end;
  unsigned long parsed = strtoul(text, &end, 16);
  if (*end || parsed > 65535 || (!allowZero && parsed == 0)) return false;
  value = parsed;
  return true;
}

static void command(char* text) {
  char* context;
  char* op = strtok_r(text, " ", &context);
  if (!op) return;
  char* a = strtok_r(nullptr, " ", &context);
  char* b = strtok_r(nullptr, " ", &context);
  if (strtok_r(nullptr, " ", &context)) { line("ERROR ARGUMENTS"); return; }
  if (!strcmp(op, "PING") && !a) { line("PONG XboxBleProbe 1"); return; }
  if (!strcmp(op, "STATUS") && !a) {
    auto* conn = Bluefruit.Connection(connection);
    line("STATUS %u %u %lu", conn && conn->connected(), conn && conn->secured(), (unsigned long)notifications);
    return;
  }
  if (!strcmp(op, "SCAN") && !a) {
    Bluefruit.Scanner.start(0); line("OK SCAN"); return;
  }
  if (!strcmp(op, "STOP") && !a) { Bluefruit.Scanner.stop(); line("OK STOP"); return; }
  if ((!strcmp(op, "CONNECT") || !strcmp(op, "FORGET")) && a && b) {
    ble_gap_addr_t address = {};
    size_t size;
    if (!unhex(a, address.addr, 6, size) || size != 6 || strlen(b) != 1 || *b < '0' || *b > '3') {
      line("ERROR ADDRESS"); return;
    }
    address.addr_type = *b - '0';
    if (!strcmp(op, "FORGET")) {
      if (address.addr_type != BLE_GAP_ADDR_TYPE_PUBLIC || connection != BLE_CONN_HANDLE_INVALID) {
        line("ERROR FORGET_REQUIRES_DISCONNECTED_PUBLIC_PEER"); return;
      }
      bond_remove_key(BLE_GAP_ROLE_CENTRAL, &address);
      line("OK FORGET");
      return;
    }
    Bluefruit.Scanner.stop();
    if (!Bluefruit.Central.connect(&address)) line("ERROR CONNECT");
    return;
  }
  if (connection == BLE_CONN_HANDLE_INVALID) { line("ERROR DISCONNECTED"); return; }
  if (!strcmp(op, "DISCONNECT") && !a) { Bluefruit.disconnect(connection); return; }
  if (!strcmp(op, "PAIR") && !a) {
    auto* conn = Bluefruit.Connection(connection);
    // Bluefruit starts encryption automatically when reconnecting with a saved bond.
    if (!conn->bonded() && !conn->requestPairing()) line("ERROR PAIR");
    else line("OK PAIR");
    return;
  }
  uint16_t start, end;
  uint32_t status;
  if (!strcmp(op, "SERVICES") && !b && handleNumber(a, start)) {
    status = sd_ble_gattc_primary_services_discover(connection, start, nullptr);
  } else if ((!strcmp(op, "CHARS") || !strcmp(op, "DESCS")) && handleNumber(a, start) && handleNumber(b, end) && start <= end) {
    ble_gattc_handle_range_t range = {start, end};
    status = !strcmp(op, "CHARS") ? sd_ble_gattc_characteristics_discover(connection, &range)
                                   : sd_ble_gattc_descriptors_discover(connection, &range);
  } else if (!strcmp(op, "READ") && handleNumber(a, start) && handleNumber(b, end, true)) {
    status = sd_ble_gattc_read(connection, start, end);
  } else if ((!strcmp(op, "WRITE") || !strcmp(op, "WRITE_CMD")) && handleNumber(a, start) && b) {
    static uint8_t data[256];
    size_t size;
    if (!unhex(b, data, sizeof(data), size) || !size) { line("ERROR HEX"); return; }
    ble_gattc_write_params_t params = {};
    bool withoutResponse = !strcmp(op, "WRITE_CMD");
    if (pendingWriteCommand) { line("ERROR WRITE_COMMAND_BUSY"); return; }
    params.write_op = withoutResponse ? BLE_GATT_OP_WRITE_CMD : BLE_GATT_OP_WRITE_REQ;
    params.handle = start;
    params.len = size;
    params.p_value = data;
    if (withoutResponse) pendingWriteCommand = start;
    status = sd_ble_gattc_write(connection, &params);
    if (status != NRF_SUCCESS) pendingWriteCommand = 0;
  } else {
    line("ERROR ARGUMENTS"); return;
  }
  // Submission success is separate from the controller's ATT response.
  if (status != NRF_SUCCESS) line("ERROR SOFTDEVICE %08lx", (unsigned long)status);
}

void setup() {
  Serial.begin(115200);
  outputLock = xSemaphoreCreateMutex();
  Bluefruit.begin(0, 1);
  Bluefruit.setName("XboxBleProbe");
  Bluefruit.autoConnLed(false);
  // Register the Xbox vendor base so discovery preserves its characteristic UUIDs.
  ble_uuid128_t base = {{0x0d, 0xd4, 0x98, 0x32, 0x95, 0xa7, 0x83, 0x9c,
                        0x4f, 0x4c, 0x60, 0x5f, 0, 0, 0, 0}};
  uint8_t type;
  sd_ble_uuid_vs_add(&base, &type);
  Bluefruit.setEventCallback(event);
  Bluefruit.Central.setConnectCallback(connected);
  Bluefruit.Central.setDisconnectCallback(disconnected);
  Bluefruit.Scanner.setRxCallback(scan);
  Bluefruit.Scanner.useActiveScan(true);
  Bluefruit.Scanner.restartOnDisconnect(false);
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r') continue;
    if (c == '\n') {
      input[inputSize] = 0;
      if (overflow) line("ERROR LINE_TOO_LONG");
      else command(input);
      inputSize = 0;
      overflow = false;
    } else if (inputSize < sizeof(input) - 1) input[inputSize++] = c;
    else overflow = true;
  }
  delay(1);
}
