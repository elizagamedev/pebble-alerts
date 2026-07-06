#include <pebble.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#define MSG_POST_SETTINGS 0u
#define MSG_POST_ALERTS 1u

#define KEY_MSG_TYPE 0u
#define KEY_ALERT_COUNT 1u
#define KEY_ALERTS_BASE 2u
#define ALERT_STRIDE 9u

#define AFIELD_ID 0u
#define AFIELD_CAL_NAME 1u
#define AFIELD_TITLE 2u
#define AFIELD_DETAILS 3u
#define AFIELD_LOCATION 4u
#define AFIELD_START_TIME 5u
#define AFIELD_END_TIME 6u
#define AFIELD_ALERT_TIME 7u
#define AFIELD_COLOR 8u

typedef struct {
  uint32_t id;
  char calendar_name[48];
  char title[64];
  char details[128];
  char location[64];
  uint32_t start_time;
  uint32_t end_time;
  uint32_t alert_time;
  GColor color;
} AlertData;

static Window *s_window;
static TextLayer *s_cal_name_layer;
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

static AlertData s_current_alert;

static char s_time_buf[16];
static char s_start_time_buf[16];
static char s_end_time_buf[16];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// Send AppMessage response back to phone.
static void prv_send_response(uint8_t value) {
  DictionaryIterator *out_iter;
  AppMessageResult result = app_message_outbox_begin(&out_iter);
  if (result == APP_MSG_OK) {
    dict_write_uint8(out_iter, 1, value);
    app_message_outbox_send();
  }
}

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

// ---------------------------------------------------------------------------
// Dummy data
// ---------------------------------------------------------------------------

static void prv_load_dummy_data(void) {
  strncpy(s_current_alert.calendar_name, "Work", sizeof(s_current_alert.calendar_name));
  strncpy(s_current_alert.title, "Team Meeting At the thing let's keep going",
          sizeof(s_current_alert.title));
  strncpy(s_current_alert.details, "Weekly sync with the entire team. Bring your laptop.",
          sizeof(s_current_alert.details));
  strncpy(s_current_alert.location, "Conference Room 1", sizeof(s_current_alert.location));
  s_current_alert.start_time = (uint32_t)(time(NULL)) + 5 * 60;
  s_current_alert.end_time = (uint32_t)(time(NULL) + 10 * 60);
  s_current_alert.color = GColorCobaltBlue;
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

static void prv_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  if (units_changed & MINUTE_UNIT) {
    prv_update_time();
  }
}

// ---------------------------------------------------------------------------
// Click handlers
// ---------------------------------------------------------------------------

static void prv_back_click_handler(ClickRecognizerRef recognizer, void *context) {
  // TODO: animate a "nudge" to indicate this is not allowed?
  vibes_double_pulse();
}

static void prv_snooze_click_handler(ClickRecognizerRef recognizer, void *context) {
  // TODO: increment alert time by snooze time
  window_stack_pop(true);
}

static void prv_dismiss_click_handler(ClickRecognizerRef recognizer, void *context) {
  // TODO: remove alert
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
// Window load / unload
// ---------------------------------------------------------------------------

static void prv_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  int16_t width = bounds.size.w - ACTION_BAR_WIDTH;
  int16_t text_width = width - 2 * H_MARGIN;

  AlertData *a = &s_current_alert;
  prv_format_time(s_start_time_buf, sizeof(s_start_time_buf), a->start_time);
  prv_format_time(s_end_time_buf, sizeof(s_end_time_buf), a->end_time);

  GColor bg_color = PBL_IF_COLOR_ELSE(a->color, GColorWhite);
  GColor fg_color = PBL_IF_COLOR_ELSE(gcolor_legible_over(a->color), GColorBlack);

  window_set_background_color(window, bg_color);

  // Calendar name
  GRect layer_bounds = GRect(H_MARGIN, V_MARGIN, text_width / 2, 18);
  s_cal_name_layer = text_layer_create(layer_bounds);
  text_layer_set_text(s_cal_name_layer, a->calendar_name);
  text_layer_set_font(s_cal_name_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_color(s_cal_name_layer, fg_color);
  text_layer_set_background_color(s_cal_name_layer, bg_color);
  text_layer_set_overflow_mode(s_cal_name_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(root, text_layer_get_layer(s_cal_name_layer));

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
  text_layer_set_text(s_title_layer, a->title);
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
  if (*a->location) {
    layer_bounds.origin.y += layer_bounds.size.h;
    layer_bounds.size.h = 18;
    s_location_layer = text_layer_create(layer_bounds);
    text_layer_set_text(s_location_layer, a->location);
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
  if (*a->details) {
    layer_bounds.origin.y += layer_bounds.size.h;
    layer_bounds.size.h = bounds.size.h - layer_bounds.origin.y - V_MARGIN;
    s_details_layer = text_layer_create(layer_bounds);
    text_layer_set_text(s_details_layer, a->details);
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
  tick_timer_service_subscribe(MINUTE_UNIT, prv_tick_handler);
}

static void prv_window_unload(Window *window) {
  tick_timer_service_unsubscribe();

  window_destroy(s_window);

  text_layer_destroy(s_cal_name_layer);
  text_layer_destroy(s_time_layer);
  text_layer_destroy(s_title_layer);
  text_layer_destroy(s_start_time_layer);
  text_layer_destroy(s_end_time_layer);
  text_layer_destroy(s_location_layer);
  text_layer_destroy(s_details_layer);
  action_bar_layer_destroy(s_action_bar);
}

// ---------------------------------------------------------------------------
// AppMessage inbox
// ---------------------------------------------------------------------------

static void prv_inbox_received_callback(DictionaryIterator *iter, void *context) {
  Tuple *type_t = dict_find(iter, KEY_MSG_TYPE);
  if (!type_t || type_t->value->uint32 != MSG_POST_ALERTS) return;

  Tuple *count_t = dict_find(iter, KEY_ALERT_COUNT);
  if (!count_t || count_t->value->uint32 == 0) return;

  uint32_t base = KEY_ALERTS_BASE;
  AlertData *a = &s_current_alert;

  Tuple *id_t = dict_find(iter, base + AFIELD_ID);
  Tuple *cal_t = dict_find(iter, base + AFIELD_CAL_NAME);
  Tuple *tit_t = dict_find(iter, base + AFIELD_TITLE);
  Tuple *det_t = dict_find(iter, base + AFIELD_DETAILS);
  Tuple *loc_t = dict_find(iter, base + AFIELD_LOCATION);
  Tuple *st_t = dict_find(iter, base + AFIELD_START_TIME);
  Tuple *et_t = dict_find(iter, base + AFIELD_END_TIME);
  Tuple *at_t = dict_find(iter, base + AFIELD_ALERT_TIME);
  Tuple *col_t = dict_find(iter, base + AFIELD_COLOR);

  if (!id_t || !tit_t || !st_t) return;

  a->id = id_t->value->uint32;

  memset(a->calendar_name, 0, sizeof(a->calendar_name));
  memset(a->title, 0, sizeof(a->title));
  memset(a->details, 0, sizeof(a->details));
  memset(a->location, 0, sizeof(a->location));

  if (cal_t) strncpy(a->calendar_name, cal_t->value->cstring, sizeof(a->calendar_name) - 1);
  if (tit_t) strncpy(a->title, tit_t->value->cstring, sizeof(a->title) - 1);
  if (det_t) strncpy(a->details, det_t->value->cstring, sizeof(a->details) - 1);
  if (loc_t) strncpy(a->location, loc_t->value->cstring, sizeof(a->location) - 1);

  a->start_time = st_t ? st_t->value->uint32 : 0;
  a->end_time = et_t ? et_t->value->uint32 : 0;
  a->alert_time = at_t ? at_t->value->uint32 : 0;
  a->color = col_t ? (GColor){.argb = col_t->value->uint8} : GColorBlue;
}

// ---------------------------------------------------------------------------
// Init / deinit / main
// ---------------------------------------------------------------------------

static void prv_init(void) {
  prv_load_dummy_data();

  s_icon_snooze = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ICON_SNOOZE);
  s_icon_dismiss = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ICON_DISMISS);
  s_icon_read_more = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ICON_READ_MORE);

  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers){
                                           .load = prv_window_load,
                                           .unload = prv_window_unload,
                                       });
  window_stack_push(s_window, true);
}

static void prv_deinit(void) {
  gbitmap_destroy(s_icon_snooze);
  gbitmap_destroy(s_icon_dismiss);
  gbitmap_destroy(s_icon_read_more);
}

int main(void) {
  prv_init();

  app_message_open(3072, 64);
  app_message_register_inbox_received(prv_inbox_received_callback);

  app_event_loop();
  prv_deinit();

  return 0;
}
