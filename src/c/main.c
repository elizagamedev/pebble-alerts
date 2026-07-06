#include <pebble.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#define MAX_ALERTS 5u

#define MESSAGE_VERSION 0u

#define MESSAGE_KEY_VERSION 0u
#define MESSAGE_KEY_HEADER 1u
#define MESSAGE_KEY_STRING_HEAP 2u

typedef enum {
  APP_MODE_REFRESH,
  APP_MODE_ALERT,
} AppMode;

typedef struct {
  uint32_t snooze_duration;
  uint32_t num_alerts;
  uint32_t string_heap_size;
  uint32_t _reserved;
} Settings;

typedef struct {
  uint32_t id;
  time_t alert_time;
  time_t start_time;
  time_t end_time;
  GColor color;
  const char *calendar;
  const char *title;
  const char *details;
  const char *location;
  uint32_t _reserved;
} AlertData;

typedef struct {
  uint32_t id;
  time_t alert_time;
} DismissedAlert;

typedef struct {
  Settings settings;
  AlertData alerts[MAX_ALERTS];
  DismissedAlert dismissed[MAX_ALERTS];
} PersistHeader;

typedef struct {
  PersistHeader header;
  char string_heap[4096];
} Persist;

static AppMode s_app_mode;
static bool s_persist_dirty;

static Persist s_persist;
static Persist s_persist_backbuffer;

// UI elements
static Window *s_window;
static TextLayer *s_calendar_layer;
static TextLayer *s_time_layer;
static TextLayer *s_title_layer;
static TextLayer *s_start_time_layer;
static TextLayer *s_end_time_layer;
static TextLayer *s_location_layer;
static TextLayer *s_details_layer;

// Action bar elements
static ActionBarLayer *s_action_bar;
static GBitmap *s_icon_snooze;
static GBitmap *s_icon_dismiss;
static GBitmap *s_icon_read_more;

// Backing UI data
static const AlertData *s_alert_queue[MAX_ALERTS];
static char s_time_buf[16];
static char s_start_time_buf[16];
static char s_end_time_buf[16];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// Format an epoch as a local clock string into buf.
static void prv_format_time(char *buf, size_t len, uint32_t epoch) {
  time_t t = (time_t)epoch;
  struct tm *tm_info = localtime(&t);
  if (clock_is_24h_style()) {
    strftime(buf, len, "%H:%M", tm_info);
  } else {
    strftime(buf, len, "%l:%M %p", tm_info);
  }
}

static void prv_dismiss(Persist *persist, const AlertData *alert) {
  int32_t insert_index = -1;
  for (uint32_t i = 0; i < MAX_ALERTS; i++) {
    if (memcmp(&persist->header.dismissed[i], alert, sizeof(DismissedAlert)) == 0) {
      return;
    }
    if (persist->header.dismissed[i].alert_time == 0) {
      insert_index = i;
    }
  }
  if (insert_index == -1) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "dismiss set overflow");
  } else {
    memcpy(&persist->header.dismissed[insert_index], alert, sizeof(DismissedAlert));
    s_persist_dirty = true;
  }
}

static bool prv_is_dismissed(const Persist *persist, const AlertData *alert) {
  for (uint32_t i = 0; i < MAX_ALERTS; i++) {
    if (memcmp(&persist->header.dismissed[i], alert, sizeof(DismissedAlert)) == 0) {
      return true;
    }
  }
  return false;
}

static void prv_clear_stale_dismissed_alerts(Persist *persist) {
  for (uint32_t i = 0; i < MAX_ALERTS; i++) {
    for (uint32_t j = 0; j < persist->header.settings.num_alerts; j++) {
      if (memcmp(&persist->header.dismissed[i], &persist->header.alerts[j],
                 sizeof(DismissedAlert)) == 0) {
        goto next;
      }
    }
    memset(&persist->header.dismissed[i], 0, sizeof(DismissedAlert));
  next:;
  }
}

// ---------------------------------------------------------------------------
// Display update
// ---------------------------------------------------------------------------

static void prv_update_time() {
  prv_format_time(s_time_buf, sizeof(s_time_buf), time(NULL));
  layer_mark_dirty(text_layer_get_layer(s_time_layer));
}

// ---------------------------------------------------------------------------
// Tick handler — update "time until" every minute
// ---------------------------------------------------------------------------

static void prv_alert_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  if (units_changed & MINUTE_UNIT) {
    prv_update_time();
  }
}

// ---------------------------------------------------------------------------
// Click handlers
// ---------------------------------------------------------------------------

static void prv_fail_to_snooze() {
  // TODO: animate a "nudge" to indicate this is not allowed?
  vibes_double_pulse();
}

static void prv_back_click_handler(ClickRecognizerRef recognizer, void *context) {
  prv_fail_to_snooze();
}

static void prv_snooze_click_handler(ClickRecognizerRef recognizer, void *context) {
  const AlertData *alert = s_alert_queue[0];
  WakeupId id =
      wakeup_schedule(time(NULL) + s_persist.header.settings.snooze_duration, alert->id, false);
  if (id >= 0) {
    APP_LOG(APP_LOG_LEVEL_INFO, "snooze scheduled for alert %u", alert->id);
    window_stack_pop(true);
  } else {
    APP_LOG(APP_LOG_LEVEL_INFO, "unable to schedule snooze for alert %u: %d", alert->id, id);
    prv_fail_to_snooze();
  }
}

static void prv_dismiss_click_handler(ClickRecognizerRef recognizer, void *context) {
  prv_dismiss(&s_persist, s_alert_queue[0]);
  window_stack_pop(true);
}

static void prv_read_more_click_handler(ClickRecognizerRef recognizer, void *context) {
  // TODO: create a new details window and push it onto the stack. The details window show the title
  // and details unabridged and allow scrolling.
}

static void prv_click_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_BACK, prv_back_click_handler);
  window_single_click_subscribe(BUTTON_ID_UP, prv_snooze_click_handler);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_read_more_click_handler);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_dismiss_click_handler);
}

// ---------------------------------------------------------------------------
// Layout constants
// ---------------------------------------------------------------------------

#define H_MARGIN 10
#define H_MARGIN_TIME 30
#define V_MARGIN 6

// ---------------------------------------------------------------------------
// Alert window lifecycle
// ---------------------------------------------------------------------------

static void prv_alert_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  int16_t width = bounds.size.w - ACTION_BAR_WIDTH;
  int16_t text_width = width - 2 * H_MARGIN;

  const AlertData *alert = s_alert_queue[0];

  prv_format_time(s_start_time_buf, sizeof(s_start_time_buf), alert->start_time);
  prv_format_time(s_end_time_buf, sizeof(s_end_time_buf), alert->end_time);

  GColor bg_color = PBL_IF_COLOR_ELSE(alert->color, GColorWhite);
  GColor fg_color = PBL_IF_COLOR_ELSE(gcolor_legible_over(alert->color), GColorBlack);

  window_set_background_color(window, bg_color);

  // Calendar name
  GRect layer_bounds = GRect(H_MARGIN, V_MARGIN, text_width / 2, 18);
  s_calendar_layer = text_layer_create(layer_bounds);
  text_layer_set_text(s_calendar_layer, alert->calendar);
  text_layer_set_font(s_calendar_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_color(s_calendar_layer, fg_color);
  text_layer_set_background_color(s_calendar_layer, bg_color);
  text_layer_set_overflow_mode(s_calendar_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(root, text_layer_get_layer(s_calendar_layer));

  // Time
  layer_bounds.origin.x += text_width / 2;
  s_time_layer = text_layer_create(layer_bounds);
  text_layer_set_text(s_time_layer, s_time_buf);
  text_layer_set_font(s_time_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_color(s_time_layer, fg_color);
  text_layer_set_background_color(s_time_layer, bg_color);
  text_layer_set_text_alignment(s_time_layer, GTextAlignmentRight);
  layer_add_child(root, text_layer_get_layer(s_time_layer));
  prv_update_time();

  // Title (up to 2 lines)
  layer_bounds.origin.x = H_MARGIN;
  layer_bounds.origin.y += layer_bounds.size.h;
  layer_bounds.size.w = text_width;
  // TODO: see TODO below for note on magic constant here
  layer_bounds.size.h = 28 * 2 + 4;
  s_title_layer = text_layer_create(layer_bounds);
  text_layer_set_text(s_title_layer, alert->title);
  text_layer_set_font(s_title_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_text_color(s_title_layer, fg_color);
  text_layer_set_background_color(s_title_layer, bg_color);
  text_layer_set_overflow_mode(s_title_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(root, text_layer_get_layer(s_title_layer));

  // Start time
  //
  // TODO: The content size seems to either ignore the ascenders, or maybe it's calculated against
  // an origin that isn't set to 0,0 of the layer bounds. Account for this discrepency with
  // something more robust than a magic constant.
  layer_bounds.origin.y += text_layer_get_content_size(s_title_layer).h + 4;
  layer_bounds.origin.x = H_MARGIN_TIME;
  layer_bounds.size.w = width - 2 * H_MARGIN_TIME;
  layer_bounds.size.h = 26;
  s_start_time_layer = text_layer_create(layer_bounds);
  text_layer_set_font(s_start_time_layer,
                      fonts_get_system_font(FONT_KEY_LECO_26_BOLD_NUMBERS_AM_PM));
  text_layer_set_text(s_start_time_layer, s_start_time_buf);
  text_layer_set_text_color(s_start_time_layer, fg_color);
  text_layer_set_background_color(s_start_time_layer, bg_color);
  layer_add_child(root, text_layer_get_layer(s_start_time_layer));

  // End time
  layer_bounds.origin.y += layer_bounds.size.h;
  s_end_time_layer = text_layer_create(layer_bounds);
  text_layer_set_font(s_end_time_layer, fonts_get_system_font(FONT_KEY_LECO_26_BOLD_NUMBERS_AM_PM));
  text_layer_set_text(s_end_time_layer, s_end_time_buf);
  text_layer_set_text_color(s_end_time_layer, fg_color);
  text_layer_set_background_color(s_end_time_layer, bg_color);
  text_layer_set_text_alignment(s_end_time_layer, GTextAlignmentRight);
  layer_add_child(root, text_layer_get_layer(s_end_time_layer));

  layer_bounds.origin.x = H_MARGIN;
  layer_bounds.size.w = text_width;

  // Location
  if (*alert->location) {
    layer_bounds.origin.y += layer_bounds.size.h;
    layer_bounds.size.h = 18;
    s_location_layer = text_layer_create(layer_bounds);
    text_layer_set_text(s_location_layer, alert->location);
    text_layer_set_font(s_location_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
    text_layer_set_text_color(s_location_layer, fg_color);
    text_layer_set_background_color(s_location_layer, bg_color);
    text_layer_set_overflow_mode(s_location_layer, GTextOverflowModeTrailingEllipsis);
    text_layer_set_text_alignment(s_location_layer, GTextAlignmentCenter);
    layer_add_child(root, text_layer_get_layer(s_location_layer));
  } else {
    s_location_layer = NULL;
  }

  // Details
  if (*alert->details) {
    layer_bounds.origin.y += layer_bounds.size.h;
    layer_bounds.size.h = bounds.size.h - layer_bounds.origin.y - V_MARGIN;
    s_details_layer = text_layer_create(layer_bounds);
    text_layer_set_text(s_details_layer, alert->details);
    text_layer_set_font(s_details_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24));
    text_layer_set_text_color(s_details_layer, fg_color);
    text_layer_set_background_color(s_details_layer, bg_color);
    text_layer_set_overflow_mode(s_details_layer, GTextOverflowModeTrailingEllipsis);
    layer_add_child(root, text_layer_get_layer(s_details_layer));
  } else {
    s_details_layer = NULL;
  }

  // Action bar
  s_action_bar = action_bar_layer_create();
  action_bar_layer_set_background_color(s_action_bar, GColorBlack);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_UP, s_icon_snooze);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_SELECT, s_icon_read_more);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_DOWN, s_icon_dismiss);
  action_bar_layer_set_click_config_provider(s_action_bar, prv_click_provider);
  action_bar_layer_add_to_window(s_action_bar, window);

  // Subscribe to time updates to update current time.
  tick_timer_service_subscribe(MINUTE_UNIT, prv_alert_tick_handler);
}

static void prv_alert_window_unload(Window *window) {
  tick_timer_service_unsubscribe();

  window_destroy(window);

  text_layer_destroy(s_calendar_layer);
  text_layer_destroy(s_time_layer);
  text_layer_destroy(s_title_layer);
  text_layer_destroy(s_start_time_layer);
  text_layer_destroy(s_end_time_layer);
  text_layer_destroy(s_location_layer);
  text_layer_destroy(s_details_layer);
  action_bar_layer_destroy(s_action_bar);
}

static void prv_alert_init_ui() {
  s_icon_snooze = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ICON_SNOOZE);
  s_icon_dismiss = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ICON_DISMISS);
  s_icon_read_more = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ICON_READ_MORE);

  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers){
                                           .load = prv_alert_window_load,
                                           .unload = prv_alert_window_unload,
                                       });
  window_stack_push(s_window, true);
}

static void prv_alert_deinit_ui() {
  gbitmap_destroy(s_icon_snooze);
  gbitmap_destroy(s_icon_dismiss);
  gbitmap_destroy(s_icon_read_more);
}

// ---------------------------------------------------------------------------
// Refresh window lifecycle
// ---------------------------------------------------------------------------

static void prv_refresh_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);

  s_calendar_layer = text_layer_create(GRect(0, (bounds.size.h - 32) / 2, bounds.size.w, 32));
  text_layer_set_text(s_calendar_layer, "Refreshing...");
  text_layer_set_font(s_calendar_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_text_alignment(s_calendar_layer, GTextAlignmentCenter);
  layer_add_child(root, text_layer_get_layer(s_calendar_layer));
}

static void prv_refresh_window_unload(Window *window) {
  window_destroy(window);
  text_layer_destroy(s_calendar_layer);
}

static void prv_refresh_init_ui() {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers){.load = prv_refresh_window_load,
                                                        .unload = prv_refresh_window_unload});
  window_stack_push(s_window, false);
}

static void prv_refresh_deinit_ui() {}

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------
static void prv_persist_relocate(Persist *persist, int32_t sign) {
  int32_t offset = sign * (int32_t)&persist->string_heap[0];
  for (uint32_t i = 0; i < persist->header.settings.num_alerts; i++) {
    persist->header.alerts[i].calendar += offset;
    persist->header.alerts[i].title += offset;
    persist->header.alerts[i].details += offset;
    persist->header.alerts[i].location += offset;
  }
}

static bool prv_persist_read_header(Persist *persist) {
  if (!persist_exists(MESSAGE_KEY_VERSION)) {
    // No data has been persisted yet.
    return false;
  }
  uint32_t version = persist_read_int(MESSAGE_KEY_VERSION);
  if (version != MESSAGE_VERSION) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "persist version too new: %u", version);
    return false;
  }

  if (!persist_exists(MESSAGE_KEY_HEADER)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "persist payload missing");
    return false;
  }
  int size = persist_get_size(MESSAGE_KEY_HEADER);
  if (size != sizeof(PersistHeader)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "persist payload incorrect size: %d", size);
    return false;
  }

  size = persist_read_data(MESSAGE_KEY_HEADER, &persist->header, sizeof(PersistHeader));
  if (size != sizeof(PersistHeader)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "failed to read persist payload: %d", size);
    return false;
  }

  prv_persist_relocate(persist, 1);

  return true;
}

static bool prv_persist_read(Persist *persist) {
  if (!prv_persist_read_header(persist)) {
    return false;
  }

  uint32_t chunk = 0;
  for (; chunk < persist->header.settings.string_heap_size / PERSIST_DATA_MAX_LENGTH; chunk++) {
    int size = persist_read_data(MESSAGE_KEY_STRING_HEAP + chunk,
                                 &persist->string_heap[PERSIST_DATA_MAX_LENGTH * chunk],
                                 PERSIST_DATA_MAX_LENGTH);
    if (size != PERSIST_DATA_MAX_LENGTH) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "failed to write persist string heap chunk %u: %d", chunk, size);
      return false;
    }
  }

  uint32_t remainder = persist->header.settings.string_heap_size % PERSIST_DATA_MAX_LENGTH;
  if (remainder > 0) {
    int size = persist_read_data(MESSAGE_KEY_STRING_HEAP + chunk,
                                 &persist->string_heap[PERSIST_DATA_MAX_LENGTH * chunk], remainder);
    if (size != (int)remainder) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "failed to write persist string heap chunk %u: %d", chunk, size);
      return false;
    }
  }

  return true;
}

static void prv_persist_write(Persist *persist) {
  status_t status = persist_write_int(MESSAGE_KEY_VERSION, MESSAGE_VERSION);
  if (status != 4) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "failed to write persist version: %d", status);
    return;
  }

  // Clear out any stale dismissed IDs.
  prv_clear_stale_dismissed_alerts(persist);
  prv_persist_relocate(persist, -1);

  // Persist.
  status = persist_write_data(MESSAGE_KEY_HEADER, &persist->header, sizeof(PersistHeader));
  if (status != sizeof(PersistHeader)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "failed to write persist payload: %d", status);
    return;
  }

  // String heap.
  uint32_t chunk = 0;
  for (; chunk < persist->header.settings.string_heap_size / PERSIST_DATA_MAX_LENGTH; chunk++) {
    int size = persist_write_data(MESSAGE_KEY_STRING_HEAP + chunk,
                                  &persist->string_heap[PERSIST_DATA_MAX_LENGTH * chunk],
                                  PERSIST_DATA_MAX_LENGTH);
    if (size != PERSIST_DATA_MAX_LENGTH) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "failed to write persist string heap chunk %u: %d", chunk, size);
      return;
    }
  }
  uint32_t remainder = persist->header.settings.string_heap_size % PERSIST_DATA_MAX_LENGTH;
  if (remainder > 0) {
    int size =
        persist_write_data(MESSAGE_KEY_STRING_HEAP + chunk,
                           &persist->string_heap[PERSIST_DATA_MAX_LENGTH * chunk], remainder);
    if (size != (int)remainder) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "failed to write persist string heap chunk %u: %d", chunk, size);
      return;
    }
  }
  for (; chunk < sizeof(persist->string_heap) / PERSIST_DATA_MAX_LENGTH; chunk++) {
    status = persist_delete(chunk);
    if (status != S_TRUE && status != E_DOES_NOT_EXIST) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "failed to delete persist string heap chunk %u: %d", chunk,
              status);
      return;
    }
  }
}

// ---------------------------------------------------------------------------
// AppMessage inbox
// ---------------------------------------------------------------------------

static void prv_parse_message(DictionaryIterator *iter, Persist *persist) {
  {
    Tuple *version_tuple = dict_find(iter, MESSAGE_KEY_VERSION);
    if (!version_tuple) {
      APP_LOG(APP_LOG_LEVEL_WARNING, "invalid message format");
      return;
    }
    uint32_t version = version_tuple->value->uint32;
    if (version != MESSAGE_VERSION) {
      APP_LOG(APP_LOG_LEVEL_WARNING, "message version too new: %u", version);
      return;
    }
  }

  Tuple *payload_tuple = dict_find(iter, MESSAGE_KEY_HEADER);
  if (!payload_tuple) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "message payload missing");
    return;
  }
  const uint8_t *payload = payload_tuple->value->data;

  memcpy(&persist->header.settings, &payload[0], sizeof(Settings));
  if (persist->header.settings.num_alerts > MAX_ALERTS) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "too many alerts: %u", persist->header.settings.num_alerts);
    return;
  }
  if (persist->header.settings.string_heap_size > sizeof(persist->string_heap)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "string heap too big: %u",
            persist->header.settings.string_heap_size);
    return;
  }

  const uint32_t payloads_size = sizeof(AlertData) * persist->header.settings.num_alerts;

  memcpy(persist->header.alerts, &payload[sizeof(Settings)], payloads_size);
  memcpy(persist->string_heap, &payload[sizeof(Settings) + payloads_size],
         persist->header.settings.string_heap_size);

  prv_persist_relocate(persist, 1);

  // Schedule alerts for eac

  s_persist_dirty = true;
}

static void prv_inbox_received_callback(DictionaryIterator *iter, void *context) {
  prv_parse_message(iter, (Persist *)context);
  switch (s_app_mode) {
    case APP_MODE_REFRESH:
      window_stack_pop(true);
      break;
    case APP_MODE_ALERT:
      break;
  }
}

static bool prv_begin_listening(Persist *persist) {
  app_message_set_context(persist);

  // 16 is a generous estimate of the amount of AppMessage overhead.
  AppMessageResult result = app_message_open(
      16 + sizeof(Settings) + sizeof(AlertData) * MAX_ALERTS + sizeof(persist->string_heap), 64);
  if (result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "failed to begin listening: %u", result);
    return false;
  }

  app_message_register_inbox_received(prv_inbox_received_callback);
  return true;
}

// ---------------------------------------------------------------------------
// Init & Main
// ---------------------------------------------------------------------------

static const AlertData *prv_get_alert(uint32_t alert_id) {
  time_t now = time(NULL);
  // Find the last alert which is earlier than now.
  const AlertData *best_alert = NULL;
  for (uint32_t i = 0; i < MAX_ALERTS; i++) {
    const AlertData *alert = &s_persist.header.alerts[i];
    if (alert->id == alert_id && alert->alert_time <= now) {
      best_alert = alert;
    }
  }
  if (!best_alert) {
    APP_LOG(APP_LOG_LEVEL_INFO, "woke for alert %u but it was missing", alert_id);
    return NULL;
  }
  return best_alert;
}

static void prv_wakeup_callback(WakeupId id, int32_t cookie) {
  uint32_t queue_index = 0;
  for (; queue_index < MAX_ALERTS; queue_index++) {
    if (s_alert_queue[queue_index] == NULL) {
      break;
    }
    if (s_alert_queue[queue_index]->id == (uint32_t)cookie) {
      APP_LOG(APP_LOG_LEVEL_INFO, "woke for alert %u but it was already scheduled",
              (uint32_t)cookie);
      return;
    }
  }

  if (queue_index == MAX_ALERTS) {
    APP_LOG(APP_LOG_LEVEL_INFO, "woke for alert %u but the queue is full", (uint32_t)cookie);
    return;
  }

  s_alert_queue[queue_index] = prv_get_alert((uint32_t)cookie);
}

static bool prv_alert_init() {
  prv_begin_listening(&s_persist_backbuffer);

  if (!prv_persist_read(&s_persist)) {
    // We can't display any alerts if we can't read them from disk.
    return false;
  }

  memset(s_alert_queue, 0, sizeof(s_alert_queue));

  WakeupId wakeup_id;
  int32_t cookie;
  if (wakeup_get_launch_event(&wakeup_id, &cookie)) {
    const AlertData *alert = prv_get_alert((uint32_t)cookie);
    if (alert == NULL) {
      return false;
    }
    s_alert_queue[0] = alert;
  } else {
    APP_LOG(APP_LOG_LEVEL_ERROR, "didn't wake but entered alert mode somehow");
    return false;
  }

  wakeup_service_subscribe(prv_wakeup_callback);

  return true;
}

static bool prv_refresh_init() {
  if (!prv_persist_read_header(&s_persist_backbuffer)) {
    memset(&s_persist_backbuffer, 0, sizeof(s_persist_backbuffer));
    prv_persist_relocate(&s_persist_backbuffer, 1);
  }

  if (!prv_begin_listening(&s_persist_backbuffer)) {
    return false;
  }

  return true;
}

int main() {
  s_persist_dirty = false;

  switch (launch_reason()) {
    case APP_LAUNCH_PHONE:
      s_app_mode = APP_MODE_REFRESH;
      if (prv_refresh_init()) {
        prv_refresh_init_ui();
        app_event_loop();
        prv_refresh_deinit_ui();
        if (s_persist_dirty) {
          prv_persist_write(&s_persist_backbuffer);
        }
      }
      break;
    case APP_LAUNCH_WAKEUP:
      s_app_mode = APP_MODE_ALERT;
      if (prv_alert_init()) {
        while (s_alert_queue[0]) {
          // TODO: wait for after animation?
          vibes_long_pulse();
          prv_alert_init_ui();
          app_event_loop();
          prv_alert_deinit_ui();
          for (uint32_t i = 1; i < MAX_ALERTS; i++) {
            s_alert_queue[i - 1] = s_alert_queue[i];
          }
          s_alert_queue[MAX_ALERTS - 1] = NULL;
        }
        if (s_persist_dirty) {
          memcpy(s_persist_backbuffer.header.dismissed, s_persist.header.dismissed,
                 sizeof(s_persist.header.dismissed));
          prv_persist_write(&s_persist_backbuffer);
        }
      }
      break;
    default:
      APP_LOG(APP_LOG_LEVEL_ERROR, "unsupported launch reason: %d", launch_reason());
      break;
  }

  exit_reason_set(APP_EXIT_ACTION_PERFORMED_SUCCESSFULLY);
  return 0;
}
