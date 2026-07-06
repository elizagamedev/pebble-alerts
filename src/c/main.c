/* src/c/main.c
 *
 * Calendar Alerts watchapp.
 *
 * Designed with extra-large fonts to match high-legibility system themes:
 * - Solid white background card.
 * - Bold colored top banner, with centered calendar name.
 * - Extra-large high-contrast text.
 * - Action bar on the right with Snooze, Read More, and Dismiss actions.
 */

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
static Layer *s_bg_layer;
static Layer *s_banner_layer;
static TextLayer *s_cal_name_layer;
static TextLayer *s_title_layer;
static TextLayer *s_time_layer;
static TextLayer *s_time_until_layer;
static TextLayer *s_location_layer;
static TextLayer *s_details_layer;
static TextLayer *s_read_more_layer;

// Action bar elements
static ActionBarLayer *s_action_bar;
static GBitmap *s_icon_snooze;
static GBitmap *s_icon_dismiss;
static GBitmap *s_icon_read_more;

static AlertData s_current_alert;
static bool s_has_alert = false;
static bool s_details_multiline = false;

// Static string buffers (must outlive the TextLayers that reference them)
static char s_time_buf[16];
static char s_time_until_buf[24];

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

// Format a "time until" string, e.g. "in 5 min", "in 2 h", "now", "started".
static void prv_format_time_until(char *buf, size_t len, uint32_t epoch) {
  time_t now = time(NULL);
  int32_t delta_s = (int32_t)epoch - (int32_t)now;

  if (delta_s < -60) {
    snprintf(buf, len, "started");
  } else if (delta_s < 60) {
    snprintf(buf, len, "now");
  } else if (delta_s < 3600) {
    snprintf(buf, len, "in %ld min", (long)(delta_s / 60));
  } else {
    snprintf(buf, len, "in %ld h", (long)(delta_s / 3600));
  }
}

// Returns true if the details string contains more than one visible line when
// rendered with FONT_KEY_GOTHIC_24_BOLD into a single-line-height rect.
static bool prv_details_is_multiline(const char *details, int16_t text_width) {
  if (!details || details[0] == '\0') return false;
  // A newline always means multi-line.
  if (strchr(details, '\n')) return true;
  // Measure the single-line height and compare with a one-line render.
  GFont font = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
  GRect one_line = GRect(0, 0, text_width, 30);
  GSize full_size = graphics_text_layout_get_content_size(
      details, font, one_line, GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft);
  return full_size.h > 30;
}

// ---------------------------------------------------------------------------
// Dummy data
// ---------------------------------------------------------------------------

static void prv_load_dummy_data(void) {
  s_has_alert = true;
  strncpy(s_current_alert.calendar_name, "Work", sizeof(s_current_alert.calendar_name));
  strncpy(s_current_alert.title, "Team Meeting", sizeof(s_current_alert.title));
  strncpy(s_current_alert.details, "Weekly sync with the entire team. Bring your laptop.",
          sizeof(s_current_alert.details));
  strncpy(s_current_alert.location, "Conference Room 1", sizeof(s_current_alert.location));
  s_current_alert.start_time = (uint32_t)(time(NULL) + 9 * 60);
  s_current_alert.color = GColorCobaltBlue;
}

// ---------------------------------------------------------------------------
// Layer drawing
// ---------------------------------------------------------------------------

static void prv_bg_update_proc(Layer *layer, GContext *ctx) {
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, layer_get_bounds(layer), 0, GCornerNone);
}

static void prv_banner_update_proc(Layer *layer, GContext *ctx) {
  GColor color = s_has_alert ? s_current_alert.color : GColorDarkGray;
  graphics_context_set_fill_color(ctx, color);
  graphics_fill_rect(ctx, layer_get_bounds(layer), 0, GCornerNone);
}

// ---------------------------------------------------------------------------
// Display update
// ---------------------------------------------------------------------------

static void prv_update_time_until(void) {
  if (!s_has_alert) return;
  prv_format_time_until(s_time_until_buf, sizeof(s_time_until_buf), s_current_alert.start_time);
  text_layer_set_text(s_time_until_layer, s_time_until_buf);
}

static void prv_display_current_alert(void) {
  layer_mark_dirty(s_banner_layer);

  if (!s_has_alert) {
    text_layer_set_text(s_cal_name_layer, "No Alert");
    text_layer_set_text(s_title_layer, "");
    text_layer_set_text(s_time_layer, "");
    text_layer_set_text(s_time_until_layer, "");
    text_layer_set_text(s_location_layer, "");
    text_layer_set_text(s_details_layer, "");
    text_layer_set_text(s_read_more_layer, "");
    return;
  }

  AlertData *a = &s_current_alert;
  int16_t text_w = (layer_get_bounds(window_get_root_layer(s_window)).size.w - ACTION_BAR_WIDTH -
                    2 * 10 /* H_MARGIN */);

  // Banner
  text_layer_set_text(s_cal_name_layer, a->calendar_name);
  text_layer_set_text_color(s_cal_name_layer, gcolor_legible_over(a->color));

  // Title
  text_layer_set_text(s_title_layer, a->title);
  text_layer_set_text_color(s_title_layer, GColorBlack);

  // Start time + time-until
  prv_format_time(s_time_buf, sizeof(s_time_buf), a->start_time);
  text_layer_set_text(s_time_layer, s_time_buf);
  text_layer_set_text_color(s_time_layer, GColorBlack);
  prv_update_time_until();
  text_layer_set_text_color(s_time_until_layer, GColorDarkGray);

  // Location
  text_layer_set_text(s_location_layer, a->location);
  text_layer_set_text_color(s_location_layer, GColorBlack);

  // Details: show first line only; show "Read more…" if multi-line.
  s_details_multiline = prv_details_is_multiline(a->details, text_w);
  text_layer_set_text(s_details_layer, a->details);
  text_layer_set_text_color(s_details_layer, GColorBlack);

  if (s_details_multiline) {
    text_layer_set_text(s_read_more_layer, "Read more…");
    text_layer_set_text_color(s_read_more_layer, GColorDarkGray);
    action_bar_layer_set_icon(s_action_bar, BUTTON_ID_SELECT, s_icon_read_more);
  } else {
    text_layer_set_text(s_read_more_layer, "");
    action_bar_layer_set_icon(s_action_bar, BUTTON_ID_SELECT, NULL);
  }
}

// ---------------------------------------------------------------------------
// Tick handler — update "time until" every minute
// ---------------------------------------------------------------------------

static void prv_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  prv_update_time_until();
}

// ---------------------------------------------------------------------------
// Click handlers
// ---------------------------------------------------------------------------

static void prv_snooze_click_handler(ClickRecognizerRef recognizer, void *context) {
  prv_send_response(1);
  vibes_double_pulse();
}

static void prv_dismiss_click_handler(ClickRecognizerRef recognizer, void *context) {
  prv_send_response(0);
  window_stack_pop(true);
}

static void prv_read_more_click_handler(ClickRecognizerRef recognizer, void *context) {
  // Show the full details text by expanding the details layer to fill all
  // remaining space. A second press collapses it back.
  static bool s_expanded = false;
  s_expanded = !s_expanded;

  GRect details_frame = layer_get_frame(text_layer_get_layer(s_details_layer));
  GRect read_more_frame = layer_get_frame(text_layer_get_layer(s_read_more_layer));
  GRect bg_bounds = layer_get_bounds(s_bg_layer);

  if (s_expanded) {
    // Stretch details layer to bottom of the card.
    details_frame.size.h = bg_bounds.size.h - details_frame.origin.y - 4;
    read_more_frame.size.h = 0;  // hide
    text_layer_set_overflow_mode(s_details_layer, GTextOverflowModeWordWrap);
  } else {
    details_frame.size.h = 28;  // one line
    read_more_frame.size.h = 20;
    text_layer_set_overflow_mode(s_details_layer, GTextOverflowModeTrailingEllipsis);
  }
  layer_set_frame(text_layer_get_layer(s_details_layer), details_frame);
  layer_set_frame(text_layer_get_layer(s_read_more_layer), read_more_frame);
}

static void prv_click_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_UP, prv_snooze_click_handler);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_read_more_click_handler);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_dismiss_click_handler);
  window_single_click_subscribe(BUTTON_ID_BACK, prv_snooze_click_handler);
}

// ---------------------------------------------------------------------------
// Layout constants
// ---------------------------------------------------------------------------

#define BANNER_HEIGHT 36
#define H_MARGIN 10
#define V_MARGIN 6

// ---------------------------------------------------------------------------
// Window load / unload
// ---------------------------------------------------------------------------

static void prv_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  int16_t w = bounds.size.w - ACTION_BAR_WIDTH;
  int16_t card_h = bounds.size.h;
  int16_t tw = w - 2 * H_MARGIN;  // text width

  // White card background
  s_bg_layer = layer_create(GRect(0, 0, w, card_h));
  layer_set_update_proc(s_bg_layer, prv_bg_update_proc);
  layer_add_child(root, s_bg_layer);

  // Colored banner
  s_banner_layer = layer_create(GRect(0, 0, w, BANNER_HEIGHT));
  layer_set_update_proc(s_banner_layer, prv_banner_update_proc);
  layer_add_child(s_bg_layer, s_banner_layer);

  // Calendar name — centered inside banner
  s_cal_name_layer = text_layer_create(GRect(H_MARGIN, 9, tw, 20));
  text_layer_set_font(s_cal_name_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_cal_name_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_cal_name_layer, GColorClear);
  layer_add_child(s_banner_layer, text_layer_get_layer(s_cal_name_layer));

  int16_t y = BANNER_HEIGHT + V_MARGIN;

  // Title (Gothic 28 Bold, up to 2 lines)
  s_title_layer = text_layer_create(GRect(H_MARGIN, y, tw, 62));
  text_layer_set_font(s_title_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_background_color(s_title_layer, GColorClear);
  text_layer_set_overflow_mode(s_title_layer, GTextOverflowModeWordWrap);
  layer_add_child(s_bg_layer, text_layer_get_layer(s_title_layer));
  y += 62 + V_MARGIN;

  // Start time (Gothic 24 Bold) + "time until" to its right (Gothic 18)
  s_time_layer = text_layer_create(GRect(H_MARGIN, y, tw / 2, 28));
  text_layer_set_font(s_time_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_background_color(s_time_layer, GColorClear);
  layer_add_child(s_bg_layer, text_layer_get_layer(s_time_layer));

  s_time_until_layer = text_layer_create(GRect(H_MARGIN + tw / 2, y + 6, tw / 2, 20));
  text_layer_set_font(s_time_until_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_background_color(s_time_until_layer, GColorClear);
  text_layer_set_text_alignment(s_time_until_layer, GTextAlignmentRight);
  layer_add_child(s_bg_layer, text_layer_get_layer(s_time_until_layer));
  y += 28 + V_MARGIN;

  // Location (Gothic 18 Bold, one line with trailing ellipsis)
  s_location_layer = text_layer_create(GRect(H_MARGIN, y, tw, 22));
  text_layer_set_font(s_location_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_background_color(s_location_layer, GColorClear);
  text_layer_set_overflow_mode(s_location_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(s_bg_layer, text_layer_get_layer(s_location_layer));
  y += 22 + V_MARGIN;

  // Details — first line only, truncated with ellipsis (Gothic 24 Bold)
  s_details_layer = text_layer_create(GRect(H_MARGIN, y, tw, 28));
  text_layer_set_font(s_details_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_background_color(s_details_layer, GColorClear);
  text_layer_set_overflow_mode(s_details_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(s_bg_layer, text_layer_get_layer(s_details_layer));
  y += 28 + 2;

  // "Read more…" label — shown below details when text is multi-line
  s_read_more_layer = text_layer_create(GRect(H_MARGIN, y, tw, 20));
  text_layer_set_font(s_read_more_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_background_color(s_read_more_layer, GColorClear);
  layer_add_child(s_bg_layer, text_layer_get_layer(s_read_more_layer));

  // Action bar — Snooze (up), Read More (centre), Dismiss (down)
  s_action_bar = action_bar_layer_create();
  action_bar_layer_set_background_color(s_action_bar, GColorBlack);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_UP, s_icon_snooze);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_DOWN, s_icon_dismiss);
  // Centre icon is set conditionally in prv_display_current_alert.
  action_bar_layer_set_click_config_provider(s_action_bar, prv_click_provider);
  action_bar_layer_add_to_window(s_action_bar, window);

  prv_display_current_alert();
}

static void prv_window_unload(Window *window) {
  tick_timer_service_unsubscribe();

  text_layer_destroy(s_cal_name_layer);
  text_layer_destroy(s_title_layer);
  text_layer_destroy(s_time_layer);
  text_layer_destroy(s_time_until_layer);
  text_layer_destroy(s_location_layer);
  text_layer_destroy(s_details_layer);
  text_layer_destroy(s_read_more_layer);
  layer_destroy(s_banner_layer);
  layer_destroy(s_bg_layer);
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

  s_has_alert = true;
  prv_display_current_alert();
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
  window_set_background_color(s_window, GColorBlack);
  window_set_window_handlers(s_window, (WindowHandlers){
                                           .load = prv_window_load,
                                           .unload = prv_window_unload,
                                       });
  window_stack_push(s_window, true);

  tick_timer_service_subscribe(MINUTE_UNIT, prv_tick_handler);
}

static void prv_deinit(void) {
  window_destroy(s_window);
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
