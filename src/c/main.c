#include <pebble.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#define MAX_ALERTS 6u

#define MESSAGE_VERSION 0u

#define MESSAGE_KEY_VERSION 0u
#define MESSAGE_KEY_HEADER 1u
#define MESSAGE_KEY_STRING_HEAP 2u

#define ALARM_DISMISSED ((time_t)(-1))
#define ALERT_QUEUE_EMPTY UINT32_MAX

typedef enum {
  APP_MODE_REFRESH,
  APP_MODE_ALERT,
} AppMode;

typedef enum {
  VIBE_PATTERN_NONE = 0,
  VIBE_PATTERN_SHORT = 1,
  VIBE_PATTERN_LONG = 2,
  VIBE_PATTERN_DOUBLE = 3,
} VibePatternSetting;

typedef struct {
  uint32_t snooze_duration;
  uint32_t num_alerts;
  uint32_t string_heap_size;
  VibePatternSetting vibe_pattern;
} Settings;

typedef struct {
  uint32_t id;
  time_t alert_time;
  time_t start_time;
  time_t end_time;
  GColor color;
  uint32_t calendar;
  uint32_t title;
  uint32_t details;
  uint32_t location;
  time_t alarm_time;
} AlertData;

typedef struct {
  Settings settings;
  AlertData alerts[MAX_ALERTS];
} PersistHeader;

typedef struct {
  PersistHeader header;
  char string_heap[4096];
} Persist;

static AppMode s_app_mode;
static bool s_backbuffer_dirty;
static bool s_frontbuffer_dirty;

static Persist s_persist;
static Persist s_persist_backbuffer;

// UI elements
static Window *s_window;

typedef struct {
  TextLayer *calendar_layer;
  TextLayer *time_layer;
  TextLayer *title_layer;
  TextLayer *start_time_layer;
  TextLayer *end_time_layer;
  TextLayer *location_layer;
  Layer *info_container_layer;
  ScrollLayer *details_scroll_layer;
  TextLayer *details_layer;
  Layer *content_layer;
  PropertyAnimation *prop_anim;
  PropertyAnimation *details_anims[5];
  GRect details_normal_frame;
  GRect details_expanded_frame;
  GRect details_text_normal_frame;
  GRect details_text_expanded_frame;
  GColor bg_color;
  char start_time_buf[16];
  char end_time_buf[16];
} AlertUi;

static AlertUi s_alert_ui[2];
static int s_current_alert_ui;
static bool s_is_details_view;
static PropertyAnimation *s_action_bar_anim;

// Action bar elements
static ActionBarLayer *s_action_bar;
static GBitmap *s_icon_snooze;
static GBitmap *s_icon_dismiss;
static GBitmap *s_icon_read_more;

static uint32_t s_alert_queue[MAX_ALERTS];
static char s_time_buf[16];
static int16_t s_time_width;

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

// ---------------------------------------------------------------------------
// Display update
// ---------------------------------------------------------------------------

static void prv_update_time() {
  prv_format_time(s_time_buf, sizeof(s_time_buf), time(NULL));
  layer_mark_dirty(text_layer_get_layer(s_alert_ui[s_current_alert_ui].time_layer));
}

static void prv_alert_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  if (units_changed & MINUTE_UNIT) {
    prv_update_time();
  }
}

// ---------------------------------------------------------------------------
// Click handlers
// ---------------------------------------------------------------------------

static void prv_make_alert_ui(const AlertData *alert, AlertUi *ui);
static void prv_destroy_alert_ui(const AlertUi *ui);

static void prv_details_exit() {
  s_is_details_view = false;

  AlertUi *ui = &s_alert_ui[s_current_alert_ui];
  GRect bounds = layer_get_bounds(window_get_root_layer(s_window));

  // Action bar moves back.
  GRect ab_end = layer_get_frame(action_bar_layer_get_layer(s_action_bar));
  ab_end.origin.x = bounds.size.w - ACTION_BAR_WIDTH;
  s_action_bar_anim = property_animation_create_layer_frame(
      action_bar_layer_get_layer(s_action_bar), NULL, &ab_end);
  animation_schedule((Animation *)s_action_bar_anim);

  // Info container moves back.
  GRect info_frame = layer_get_frame(ui->info_container_layer);
  info_frame.origin.x = 0;
  ui->details_anims[0] =
      property_animation_create_layer_frame(ui->info_container_layer, NULL, &info_frame);
  animation_schedule((Animation *)ui->details_anims[0]);

  // Scroll layer restores.
  GRect end_frame = ui->details_normal_frame;
  ui->details_anims[1] = property_animation_create_layer_frame(
      scroll_layer_get_layer(ui->details_scroll_layer), NULL, &end_frame);
  animation_schedule((Animation *)ui->details_anims[1]);
  scroll_layer_set_content_offset(ui->details_scroll_layer, GPointZero, true);

  // Header layers restore.
  GRect calendar_frame = layer_get_frame(text_layer_get_layer(ui->calendar_layer));
  calendar_frame.size.w -= ACTION_BAR_WIDTH;
  ui->details_anims[2] = property_animation_create_layer_frame(
      text_layer_get_layer(ui->calendar_layer), NULL, &calendar_frame);
  animation_schedule((Animation *)ui->details_anims[2]);

  GRect time_frame = layer_get_frame(text_layer_get_layer(ui->time_layer));
  time_frame.origin.x -= ACTION_BAR_WIDTH;
  ui->details_anims[3] = property_animation_create_layer_frame(text_layer_get_layer(ui->time_layer),
                                                               NULL, &time_frame);
  animation_schedule((Animation *)ui->details_anims[3]);

  // Text layer restores width.
  GRect text_frame = ui->details_text_normal_frame;
  ui->details_anims[4] = property_animation_create_layer_frame(
      text_layer_get_layer(ui->details_layer), NULL, &text_frame);
  animation_schedule((Animation *)ui->details_anims[4]);
  scroll_layer_set_content_size(ui->details_scroll_layer, text_frame.size);
}

static void prv_details_scroll(ButtonId button, bool is_repeating) {
  AlertUi *ui = &s_alert_ui[s_current_alert_ui];
  GPoint offset = scroll_layer_get_content_offset(ui->details_scroll_layer);
  switch (button) {
    case BUTTON_ID_UP:
      offset.y += 24 * 2;
      break;
    case BUTTON_ID_DOWN:
      offset.y -= 24 * 2;
      break;
    default:
      APP_LOG(APP_LOG_LEVEL_ERROR, "impossible scroll button!");
      return;
  }
  scroll_layer_set_content_offset(ui->details_scroll_layer, offset, !is_repeating);
}

static void prv_next_alert() {
  for (uint32_t i = 1; i < MAX_ALERTS; i++) {
    s_alert_queue[i - 1] = s_alert_queue[i];
  }
  s_alert_queue[MAX_ALERTS - 1] = ALERT_QUEUE_EMPTY;

  if (s_alert_queue[0] == ALERT_QUEUE_EMPTY) {
    // UI destroyed in window unload function.
    window_stack_pop(true);
    return;
  }

  AlertUi *old_ui = &s_alert_ui[s_current_alert_ui];
  s_current_alert_ui = 1 - s_current_alert_ui;
  AlertUi *new_ui = &s_alert_ui[s_current_alert_ui];

  window_set_background_color(s_window, old_ui->bg_color);

  prv_destroy_alert_ui(new_ui);
  prv_make_alert_ui(&s_persist.header.alerts[s_alert_queue[0]], new_ui);

  GRect bounds = layer_get_bounds(window_get_root_layer(s_window));

  {
    GRect start_frame = bounds;
    GRect end_frame = bounds;
    end_frame.origin.x = bounds.size.w;

    old_ui->prop_anim =
        property_animation_create_layer_frame(old_ui->content_layer, &start_frame, &end_frame);
    Animation *anim = property_animation_get_animation(old_ui->prop_anim);
    animation_set_duration(anim, 150);
    animation_set_curve(anim, AnimationCurveEaseIn);
    animation_schedule(anim);
  }

  {
    GRect start_frame = bounds;
    start_frame.origin.x = -bounds.size.w;
    GRect end_frame = bounds;

    layer_set_frame(new_ui->content_layer, start_frame);

    new_ui->prop_anim =
        property_animation_create_layer_frame(new_ui->content_layer, &start_frame, &end_frame);
    Animation *anim = property_animation_get_animation(new_ui->prop_anim);
    animation_set_duration(anim, 150);
    animation_set_curve(anim, AnimationCurveEaseIn);
    animation_schedule(anim);
  }
}

static void prv_alert_nudge() {
  vibes_short_pulse();

  GRect bounds = layer_get_bounds(window_get_root_layer(s_window));
  GRect start_frame = layer_get_frame(action_bar_layer_get_layer(s_action_bar));
  start_frame.origin.x = bounds.size.w - ACTION_BAR_WIDTH;

  GRect mid_frame = start_frame;
  mid_frame.origin.x += 8;

  PropertyAnimation *anim1 = property_animation_create_layer_frame(
      action_bar_layer_get_layer(s_action_bar), &start_frame, &mid_frame);
  animation_set_duration((Animation *)anim1, 50);

  PropertyAnimation *anim2 = property_animation_create_layer_frame(
      action_bar_layer_get_layer(s_action_bar), &mid_frame, &start_frame);
  animation_set_duration((Animation *)anim2, 50);

  PropertyAnimation *anim3 = property_animation_create_layer_frame(
      action_bar_layer_get_layer(s_action_bar), &start_frame, &mid_frame);
  animation_set_duration((Animation *)anim3, 50);

  PropertyAnimation *anim4 = property_animation_create_layer_frame(
      action_bar_layer_get_layer(s_action_bar), &mid_frame, &start_frame);
  animation_set_duration((Animation *)anim4, 50);

  Animation *seq = animation_sequence_create((Animation *)anim1, (Animation *)anim2,
                                             (Animation *)anim3, (Animation *)anim4, NULL);
  animation_set_curve(seq, AnimationCurveLinear);
  animation_schedule(seq);
}

static void prv_alert_snooze() {
  uint32_t idx = s_alert_queue[0];
  AlertData *alert = &s_persist.header.alerts[idx];
  time_t alarm_time = time(NULL) + s_persist.header.settings.snooze_duration;

  // Check if there is another alert with the same ID whose alert_time is <= alarm_time
  // and > the current alert's time. If so, dismiss this reminder instead.
  for (uint32_t i = 0; i < s_persist.header.settings.num_alerts; i++) {
    if (i != idx && s_persist.header.alerts[i].id == alert->id &&
        s_persist.header.alerts[i].alert_time > alert->alert_time &&
        s_persist.header.alerts[i].alert_time <= alarm_time) {
      APP_LOG(APP_LOG_LEVEL_INFO, "snooze for alert %u supplanted by next reminder, dismissing",
              alert->id);
      alarm_time = ALARM_DISMISSED;
      break;
    }
  }

  alert->alarm_time = alarm_time;
  s_frontbuffer_dirty = true;
  prv_next_alert();
}

static void prv_alert_dismiss() {
  s_persist.header.alerts[s_alert_queue[0]].alarm_time = ALARM_DISMISSED;
  s_frontbuffer_dirty = true;
  prv_next_alert();
}

static void prv_alert_enter_details() {
  AlertUi *ui = &s_alert_ui[s_current_alert_ui];

  if (ui->details_layer == NULL) {
    return;
  }

  s_is_details_view = true;

  GRect bounds = layer_get_bounds(window_get_root_layer(s_window));

  // Action bar moves right
  GRect ab_start = layer_get_frame(action_bar_layer_get_layer(s_action_bar));
  GRect ab_end = ab_start;
  ab_end.origin.x = bounds.size.w;
  s_action_bar_anim = property_animation_create_layer_frame(
      action_bar_layer_get_layer(s_action_bar), NULL, &ab_end);
  animation_schedule((Animation *)s_action_bar_anim);

  // Info container moves left
  GRect info_frame = layer_get_frame(ui->info_container_layer);
  info_frame.origin.x = -bounds.size.w;
  ui->details_anims[0] =
      property_animation_create_layer_frame(ui->info_container_layer, NULL, &info_frame);
  animation_schedule((Animation *)ui->details_anims[0]);

  // Scroll layer expands
  GRect end_frame = ui->details_expanded_frame;
  ui->details_anims[1] = property_animation_create_layer_frame(
      scroll_layer_get_layer(ui->details_scroll_layer), NULL, &end_frame);
  animation_schedule((Animation *)ui->details_anims[1]);

  // Header layers expand
  GRect calendar_frame = layer_get_frame(text_layer_get_layer(ui->calendar_layer));
  calendar_frame.size.w += ACTION_BAR_WIDTH;
  ui->details_anims[2] = property_animation_create_layer_frame(
      text_layer_get_layer(ui->calendar_layer), NULL, &calendar_frame);
  animation_schedule((Animation *)ui->details_anims[2]);

  GRect time_frame = layer_get_frame(text_layer_get_layer(ui->time_layer));
  time_frame.origin.x += ACTION_BAR_WIDTH;
  ui->details_anims[3] = property_animation_create_layer_frame(text_layer_get_layer(ui->time_layer),
                                                               NULL, &time_frame);
  animation_schedule((Animation *)ui->details_anims[3]);

  // Text layer expands width
  GRect text_frame = ui->details_text_expanded_frame;
  ui->details_anims[4] = property_animation_create_layer_frame(
      text_layer_get_layer(ui->details_layer), NULL, &text_frame);
  animation_schedule((Animation *)ui->details_anims[4]);
  scroll_layer_set_content_size(ui->details_scroll_layer, text_frame.size);
}

static void prv_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_alert_queue[0] == ALERT_QUEUE_EMPTY) {
    return;
  }
  ButtonId button = click_recognizer_get_button_id(recognizer);
  bool is_repeating = click_recognizer_is_repeating(recognizer);
  if (s_is_details_view) {
    switch (button) {
      case BUTTON_ID_UP:
      case BUTTON_ID_DOWN:
        prv_details_scroll(button, is_repeating);
        break;
      case BUTTON_ID_BACK:
      case BUTTON_ID_SELECT:
        if (!is_repeating) {
          prv_details_exit();
        }
        break;
      default:
        break;
    }
  } else if (!is_repeating) {
    switch (button) {
      case BUTTON_ID_UP:
        prv_alert_snooze();
        break;
      case BUTTON_ID_DOWN:
        prv_alert_dismiss();
        break;
      case BUTTON_ID_BACK:
        prv_alert_nudge();
        break;
      case BUTTON_ID_SELECT:
        prv_alert_enter_details();
        break;
      default:
        break;
    }
  }
}

static void prv_click_provider(void *context) {
  window_single_repeating_click_subscribe(BUTTON_ID_UP, 30, prv_click_handler);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, 30, prv_click_handler);
  window_single_click_subscribe(BUTTON_ID_BACK, prv_click_handler);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_click_handler);
}

// ---------------------------------------------------------------------------
// Alert window lifecycle
// ---------------------------------------------------------------------------

#define H_MARGIN 10
#define H_MARGIN_TIME 30
#define V_MARGIN 4
#define HEADER_FONT FONT_KEY_GOTHIC_18

static void prv_content_layer_update_proc(Layer *layer, GContext *ctx) {
  AlertUi *ui = (s_alert_ui[0].content_layer == layer) ? &s_alert_ui[0] : &s_alert_ui[1];
  graphics_context_set_fill_color(ctx, ui->bg_color);
  graphics_fill_rect(ctx, layer_get_bounds(layer), 0, GCornerNone);
}

static const char *prv_get_string(uint32_t offset) {
  return &s_persist.string_heap[offset];
}

static void prv_make_alert_ui(const AlertData *alert, AlertUi *ui) {
  GColor bg_color = PBL_IF_COLOR_ELSE(alert->color, GColorWhite);
  GColor fg_color = PBL_IF_COLOR_ELSE(gcolor_legible_over(alert->color), GColorBlack);

  ui->bg_color = bg_color;

  Layer *root = window_get_root_layer(s_window);
  GRect bounds = layer_get_bounds(root);
  int16_t width = bounds.size.w - ACTION_BAR_WIDTH;
  int16_t text_width = width - 2 * H_MARGIN;

  ui->content_layer = layer_create(bounds);
  ui->info_container_layer = layer_create(bounds);
  layer_set_update_proc(ui->content_layer, prv_content_layer_update_proc);
  layer_insert_below_sibling(ui->content_layer, action_bar_layer_get_layer(s_action_bar));
  layer_add_child(ui->content_layer, ui->info_container_layer);

  prv_format_time(ui->start_time_buf, sizeof(ui->start_time_buf), alert->start_time);
  prv_format_time(ui->end_time_buf, sizeof(ui->end_time_buf), alert->end_time);

  // Calendar name
  GRect layer_bounds = GRect(H_MARGIN, 0, text_width - s_time_width, STATUS_BAR_LAYER_HEIGHT);
  ui->calendar_layer = text_layer_create(layer_bounds);
  text_layer_set_text(ui->calendar_layer, prv_get_string(alert->calendar));
  text_layer_set_font(ui->calendar_layer, fonts_get_system_font(HEADER_FONT));
  text_layer_set_text_color(ui->calendar_layer, fg_color);
  text_layer_set_background_color(ui->calendar_layer, GColorClear);
  text_layer_set_overflow_mode(ui->calendar_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(ui->content_layer, text_layer_get_layer(ui->calendar_layer));

  // Time
  layer_bounds.origin.x += layer_bounds.size.w;
  layer_bounds.size.w = s_time_width;
  ui->time_layer = text_layer_create(layer_bounds);
  text_layer_set_text(ui->time_layer, s_time_buf);
  text_layer_set_font(ui->time_layer, fonts_get_system_font(HEADER_FONT));
  text_layer_set_text_color(ui->time_layer, fg_color);
  text_layer_set_background_color(ui->time_layer, GColorClear);
  text_layer_set_text_alignment(ui->time_layer, GTextAlignmentRight);
  layer_add_child(ui->content_layer, text_layer_get_layer(ui->time_layer));
  prv_update_time();

  // Title (up to 2 lines)
  layer_bounds.origin.x = H_MARGIN;
  layer_bounds.origin.y += layer_bounds.size.h;
  layer_bounds.size.w = text_width;
  layer_bounds.size.h = 28 * 2 + 4;
  ui->title_layer = text_layer_create(layer_bounds);
  text_layer_set_text(ui->title_layer, prv_get_string(alert->title));
  text_layer_set_font(ui->title_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_text_color(ui->title_layer, fg_color);
  text_layer_set_background_color(ui->title_layer, GColorClear);
  text_layer_set_overflow_mode(ui->title_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(ui->info_container_layer, text_layer_get_layer(ui->title_layer));

  // Start time
  layer_bounds.origin.y += text_layer_get_content_size(ui->title_layer).h + 4;
  layer_bounds.origin.x = H_MARGIN_TIME;
  layer_bounds.size.w = width - 2 * H_MARGIN_TIME;
  layer_bounds.size.h = 26;
  ui->start_time_layer = text_layer_create(layer_bounds);
  text_layer_set_font(ui->start_time_layer,
                      fonts_get_system_font(FONT_KEY_LECO_26_BOLD_NUMBERS_AM_PM));
  text_layer_set_text(ui->start_time_layer, ui->start_time_buf);
  text_layer_set_text_color(ui->start_time_layer, fg_color);
  text_layer_set_background_color(ui->start_time_layer, GColorClear);
  layer_add_child(ui->info_container_layer, text_layer_get_layer(ui->start_time_layer));

  // End time
  layer_bounds.origin.y += layer_bounds.size.h;
  ui->end_time_layer = text_layer_create(layer_bounds);
  text_layer_set_font(ui->end_time_layer,
                      fonts_get_system_font(FONT_KEY_LECO_26_BOLD_NUMBERS_AM_PM));
  text_layer_set_text(ui->end_time_layer, ui->end_time_buf);
  text_layer_set_text_color(ui->end_time_layer, fg_color);
  text_layer_set_background_color(ui->end_time_layer, GColorClear);
  text_layer_set_text_alignment(ui->end_time_layer, GTextAlignmentRight);
  layer_add_child(ui->info_container_layer, text_layer_get_layer(ui->end_time_layer));

  layer_bounds.origin.x = H_MARGIN;
  layer_bounds.size.w = text_width;

  // Location
  const char *location_str = prv_get_string(alert->location);
  if (*location_str) {
    layer_bounds.origin.y += layer_bounds.size.h;
    layer_bounds.size.h = 18;
    ui->location_layer = text_layer_create(layer_bounds);
    text_layer_set_text(ui->location_layer, location_str);
    text_layer_set_font(ui->location_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
    text_layer_set_text_color(ui->location_layer, fg_color);
    text_layer_set_background_color(ui->location_layer, GColorClear);
    text_layer_set_overflow_mode(ui->location_layer, GTextOverflowModeTrailingEllipsis);
    text_layer_set_text_alignment(ui->location_layer, GTextAlignmentCenter);
    layer_add_child(ui->info_container_layer, text_layer_get_layer(ui->location_layer));
  } else {
    ui->location_layer = NULL;
  }

  // Details
  const char *details_str = prv_get_string(alert->details);
  if (*details_str) {
    action_bar_layer_set_icon(s_action_bar, BUTTON_ID_SELECT, s_icon_read_more);
    layer_bounds.origin.y += layer_bounds.size.h;

    ui->details_normal_frame =
        GRect(0, layer_bounds.origin.y, bounds.size.w, bounds.size.h - layer_bounds.origin.y);
    ui->details_expanded_frame =
        GRect(0, STATUS_BAR_LAYER_HEIGHT, bounds.size.w, bounds.size.h - STATUS_BAR_LAYER_HEIGHT);

    ui->details_text_normal_frame =
        GRect(H_MARGIN, V_MARGIN, text_width, ui->details_normal_frame.size.h - V_MARGIN);

    GRect content_bounds = GRect(H_MARGIN, V_MARGIN, bounds.size.w - H_MARGIN * 2, 2000);
    GSize content_size = graphics_text_layout_get_content_size(
        details_str, fonts_get_system_font(FONT_KEY_GOTHIC_24), content_bounds,
        GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft);
    content_bounds.size.h = content_size.h + V_MARGIN * 2;
    if (content_bounds.size.h < ui->details_expanded_frame.size.h) {
      content_bounds.size.h = ui->details_expanded_frame.size.h;
    }
    ui->details_text_expanded_frame = content_bounds;

    ui->details_scroll_layer = scroll_layer_create(ui->details_normal_frame);
    layer_add_child(ui->content_layer, scroll_layer_get_layer(ui->details_scroll_layer));

    ui->details_layer = text_layer_create(ui->details_text_normal_frame);
    text_layer_set_text(ui->details_layer, details_str);
    text_layer_set_font(ui->details_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24));
    text_layer_set_text_color(ui->details_layer, fg_color);
    text_layer_set_background_color(ui->details_layer, GColorClear);
    text_layer_set_overflow_mode(ui->details_layer, GTextOverflowModeTrailingEllipsis);

    scroll_layer_set_content_size(ui->details_scroll_layer, ui->details_text_normal_frame.size);
    scroll_layer_add_child(ui->details_scroll_layer, text_layer_get_layer(ui->details_layer));
  } else {
    action_bar_layer_clear_icon(s_action_bar, BUTTON_ID_SELECT);
    ui->details_scroll_layer = NULL;
    ui->details_layer = NULL;
  }
}

static void prv_destroy_alert_ui(const AlertUi *ui) {
  text_layer_destroy(ui->calendar_layer);
  text_layer_destroy(ui->time_layer);
  text_layer_destroy(ui->title_layer);
  text_layer_destroy(ui->start_time_layer);
  text_layer_destroy(ui->end_time_layer);
  text_layer_destroy(ui->location_layer);
  layer_destroy(ui->info_container_layer);
  scroll_layer_destroy(ui->details_scroll_layer);
  text_layer_destroy(ui->details_layer);
  layer_destroy(ui->content_layer);
  for (int i = 0; i < 5; i++) {
    if (ui->details_anims[i]) {
      animation_unschedule(property_animation_get_animation(ui->details_anims[i]));
      property_animation_destroy(ui->details_anims[i]);
    }
  }
  if (ui->prop_anim) {
    animation_unschedule(property_animation_get_animation(ui->prop_anim));
    property_animation_destroy(ui->prop_anim);
  }
}

static void prv_alert_window_load(Window *window) {
  window_set_background_color(window, GColorBlack);

  Layer *root = window_get_root_layer(s_window);
  const char *test_time = clock_is_24h_style() ? "88:88" : "88:88 MM";
  GSize size = graphics_text_layout_get_content_size(test_time, fonts_get_system_font(HEADER_FONT),
                                                     layer_get_bounds(root), GTextOverflowModeFill,
                                                     GTextAlignmentLeft);
  s_time_width = size.w + 2;

  s_current_alert_ui = 0;
  memset(s_alert_ui, 0, sizeof(s_alert_ui));
  s_is_details_view = false;
  s_action_bar_anim = NULL;

  // Action bar.
  s_action_bar = action_bar_layer_create();
  action_bar_layer_set_background_color(s_action_bar, GColorBlack);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_UP, s_icon_snooze);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_DOWN, s_icon_dismiss);
  action_bar_layer_set_click_config_provider(s_action_bar, prv_click_provider);
  action_bar_layer_add_to_window(s_action_bar, window);

  prv_make_alert_ui(&s_persist.header.alerts[s_alert_queue[0]], &s_alert_ui[s_current_alert_ui]);

  // Subscribe to time updates to update current time.
  tick_timer_service_subscribe(MINUTE_UNIT, prv_alert_tick_handler);
}

static void prv_alert_window_unload(Window *window) {
  tick_timer_service_unsubscribe();
  prv_destroy_alert_ui(&s_alert_ui[0]);
  prv_destroy_alert_ui(&s_alert_ui[1]);
  action_bar_layer_destroy(s_action_bar);
}

static void prv_init_ui(void) {
  s_icon_snooze = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ICON_SNOOZE);
  s_icon_dismiss = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ICON_DISMISS);
  s_icon_read_more = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ICON_READ_MORE);

  s_window = window_create();
}

static void prv_deinit_ui(void) {
  window_destroy(s_window);
  gbitmap_destroy(s_icon_snooze);
  gbitmap_destroy(s_icon_dismiss);
  gbitmap_destroy(s_icon_read_more);
}

// ---------------------------------------------------------------------------
// Refresh window lifecycle
// ---------------------------------------------------------------------------

static TextLayer *s_refresh_text_layer;

static void prv_refresh_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);

  s_refresh_text_layer = text_layer_create(GRect(0, (bounds.size.h - 32) / 2, bounds.size.w, 32));
  text_layer_set_text(s_refresh_text_layer, "Refreshing...");
  text_layer_set_font(s_refresh_text_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_text_alignment(s_refresh_text_layer, GTextAlignmentCenter);
  layer_add_child(root, text_layer_get_layer(s_refresh_text_layer));
}

static void prv_refresh_window_unload(Window *window) {
  text_layer_destroy(s_refresh_text_layer);
}

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------
static void prv_rearm_alarms(Persist *front, Persist *back) {
  APP_LOG(APP_LOG_LEVEL_INFO, "updating backbuffer with front alarms");
  // Copy the front alarm times if they're scheduled further in the future than the back.
  if (front != back) {
    for (uint32_t f = 0; f < front->header.settings.num_alerts; f++) {
      const AlertData *front_alarm = &front->header.alerts[f];
      for (uint32_t b = 0; b < back->header.settings.num_alerts; b++) {
        AlertData *back_alarm = &back->header.alerts[b];
        if (back_alarm->id == front_alarm->id &&
            back_alarm->alert_time == front_alarm->alert_time) {
          if (back_alarm->alarm_time < front_alarm->alarm_time) {
            back_alarm->alarm_time = front_alarm->alarm_time;
          }
          break;
        }
      }
    }
  }

  APP_LOG(APP_LOG_LEVEL_INFO, "building wakeup list");
  // Reschedule all wakeups. First, gather a list of unique times we need to schedule.
  time_t wakeup_times[MAX_ALERTS];
  memset(wakeup_times, 0xFF, sizeof(wakeup_times));
  for (uint32_t b = 0; b < back->header.settings.num_alerts; b++) {
    const AlertData *alert = &back->header.alerts[b];
    uint32_t i = 0;
    for (; wakeup_times[i] != ALARM_DISMISSED; i++) {
      if (wakeup_times[i] == alert->alarm_time) {
        goto next;
      }
    }
    wakeup_times[i] = alert->alarm_time;
  next:;
  }

  // Then schedule them all.
  APP_LOG(APP_LOG_LEVEL_INFO, "scheduling wakeup list");
  wakeup_cancel_all();
  time_t now = time(NULL);
  for (uint32_t i = 0; i < MAX_ALERTS && wakeup_times[i] != ALARM_DISMISSED; i++) {
    time_t intended_time = wakeup_times[i];
    time_t real_time = intended_time < now ? now + 5 : intended_time;
    // If there's something else already in that slot, try again in the next minute.
    while (true) {
      WakeupId id = wakeup_schedule(real_time, (int32_t)intended_time, false);
      if (id == E_RANGE || id == E_INVALID_ARGUMENT) {
        // Try again one minute in the future.
        real_time += 60;
        continue;
      }
      if (id < 0) {
        APP_LOG(APP_LOG_LEVEL_WARNING, "failed to schedule alarm at %u: %d", real_time, id);
      } else {
        APP_LOG(APP_LOG_LEVEL_INFO, "scheduled alarm at %u", real_time);
      }
      break;
    }
  }
}

static bool prv_persist_read_header(Persist *persist) {
  if (!persist_exists(MESSAGE_KEY_VERSION)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "persist version key missing");
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
      APP_LOG(APP_LOG_LEVEL_ERROR, "failed to read persist string heap chunk %u: %d", chunk, size);
      return false;
    }
  }

  uint32_t remainder = persist->header.settings.string_heap_size % PERSIST_DATA_MAX_LENGTH;
  if (remainder > 0) {
    int size = persist_read_data(MESSAGE_KEY_STRING_HEAP + chunk,
                                 &persist->string_heap[PERSIST_DATA_MAX_LENGTH * chunk], remainder);
    if (size != (int)remainder) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "failed to read persist string heap chunk %u: %d", chunk, size);
      return false;
    }
  }

  return true;
}

static bool prv_persist_write_header(Persist *persist) {
  status_t status = persist_write_int(MESSAGE_KEY_VERSION, MESSAGE_VERSION);
  if (status != 4) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "failed to write persist version: %d", status);
    return false;
  }

  status = persist_write_data(MESSAGE_KEY_HEADER, &persist->header, sizeof(PersistHeader));

  if (status != sizeof(PersistHeader)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "failed to write persist payload: %d", status);
    return false;
  }

  return true;
}

static void prv_persist_write(Persist *persist) {
  if (!prv_persist_write_header(persist)) {
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
    chunk++;
  }
  for (; chunk < sizeof(persist->string_heap) / PERSIST_DATA_MAX_LENGTH; chunk++) {
    status_t status = persist_delete(MESSAGE_KEY_STRING_HEAP + chunk);
    if (status != S_TRUE && status != E_DOES_NOT_EXIST) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "failed to delete persist string heap chunk %u: %d", chunk,
              (int)status);
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

  APP_LOG(APP_LOG_LEVEL_INFO,
          "Received %u alerts:", (unsigned int)persist->header.settings.num_alerts);
  for (uint32_t i = 0; i < persist->header.settings.num_alerts; i++) {
    char time_str[32];
    time_t t = persist->header.alerts[i].alert_time;
    struct tm *tm_info = localtime(&t);
    strftime(time_str, sizeof(time_str), "%Y-%m-%d %H:%M:%S", tm_info);
    APP_LOG(APP_LOG_LEVEL_INFO, " - Alert %u: %s at %s", (unsigned int)i,
            &persist->string_heap[persist->header.alerts[i].title], time_str);
  }

  s_backbuffer_dirty = true;
}

static void prv_inbox_received_callback(DictionaryIterator *iter, void *context) {
  APP_LOG(APP_LOG_LEVEL_INFO, "inbox_received_callback: parsing message");
  prv_parse_message(iter, (Persist *)context);
  APP_LOG(APP_LOG_LEVEL_INFO, "inbox_received_callback: parsed message, backbuffer_dirty = %d",
          s_backbuffer_dirty);
  switch (s_app_mode) {
    case APP_MODE_REFRESH:
      APP_LOG(APP_LOG_LEVEL_INFO, "inbox_received_callback: popping window");
      window_stack_pop(true);
      break;
    case APP_MODE_ALERT:
      APP_LOG(APP_LOG_LEVEL_INFO, "inbox_received_callback: ignoring in alert mode");
      break;
  }
}

static bool prv_begin_listening(Persist *persist) {
  app_message_set_context(persist);

  uint32_t payload_size =
      sizeof(Settings) + sizeof(AlertData) * MAX_ALERTS + sizeof(persist->string_heap);
  uint32_t inbox_size = dict_calc_buffer_size(2, sizeof(uint32_t), payload_size);

  AppMessageResult result = app_message_open(inbox_size, 0);
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

static void prv_queue_alerts(time_t alarm_time) {
  // We can overwrite the entire except for the head, which the user is currently viewing.
  uint32_t head_value = s_alert_queue[0];
  uint32_t tail = head_value == ALERT_QUEUE_EMPTY ? 0 : 1;

  for (uint32_t i = 0; i < s_persist.header.settings.num_alerts; i++) {
    if (i == head_value) {
      continue;
    }
    if (s_persist.header.alerts[i].alarm_time != ALARM_DISMISSED &&
        s_persist.header.alerts[i].alarm_time <= alarm_time) {
      s_alert_queue[tail++] = i;
    }
  }
}

static void prv_wakeup_callback(WakeupId id, int32_t cookie) {
  prv_queue_alerts((time_t)cookie);
}

static bool prv_alert_init() {
  s_app_mode = APP_MODE_ALERT;
  prv_begin_listening(&s_persist_backbuffer);

  if (!prv_persist_read(&s_persist)) {
    wakeup_cancel_all();
    return false;
  }

  // Initialize alert queue.
  memset(s_alert_queue, 0xFF, sizeof(s_alert_queue));  // ALERT_QUEUE_EMPTY

  WakeupId wakeup_id;
  int32_t cookie;
  if (!wakeup_get_launch_event(&wakeup_id, &cookie)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "didn't wake but entered alert mode somehow");
    return false;
  }

  prv_queue_alerts((time_t)cookie);
  wakeup_service_subscribe(prv_wakeup_callback);

  if (s_alert_queue[0] == ALERT_QUEUE_EMPTY) {
    return false;
  }

  switch (s_persist.header.settings.vibe_pattern) {
    case VIBE_PATTERN_NONE:
    default:
      break;
    case VIBE_PATTERN_SHORT:
      vibes_short_pulse();
      break;
    case VIBE_PATTERN_LONG:
      vibes_long_pulse();
      break;
    case VIBE_PATTERN_DOUBLE:
      vibes_double_pulse();
      break;
  }

  return true;
}

static bool prv_refresh_init() {
  s_app_mode = APP_MODE_REFRESH;
  APP_LOG(APP_LOG_LEVEL_INFO, "prv_refresh_init: starting");
  if (!prv_persist_read_header(&s_persist)) {
    APP_LOG(APP_LOG_LEVEL_WARNING,
            "prv_refresh_init: persist_read_header failed, clearing persist");
    memset(&s_persist, 0, sizeof(s_persist));
  } else {
    APP_LOG(APP_LOG_LEVEL_INFO, "prv_refresh_init: persist_read_header success");
  }

  if (!prv_begin_listening(&s_persist_backbuffer)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "prv_refresh_init: begin_listening failed");
    return false;
  }

  APP_LOG(APP_LOG_LEVEL_INFO, "prv_refresh_init: success");
  return true;
}

int main() {
  s_backbuffer_dirty = false;
  s_frontbuffer_dirty = false;
  exit_reason_set(APP_EXIT_ACTION_PERFORMED_SUCCESSFULLY);

  switch (launch_reason()) {
    case APP_LAUNCH_PHONE:
      if (!prv_refresh_init()) {
        return 0;
      }
      break;
    case APP_LAUNCH_WAKEUP:
      if (!prv_alert_init()) {
        return 0;
      }
      break;
    default:
      APP_LOG(APP_LOG_LEVEL_ERROR, "unsupported launch reason: %d", launch_reason());
      return 0;
  }

  prv_init_ui();
  switch (s_app_mode) {
    case APP_MODE_REFRESH:
      window_set_window_handlers(s_window, (WindowHandlers){
                                               .load = prv_refresh_window_load,
                                               .unload = prv_refresh_window_unload,
                                           });
      break;
    case APP_MODE_ALERT:
      window_set_window_handlers(s_window, (WindowHandlers){
                                               .load = prv_alert_window_load,
                                               .unload = prv_alert_window_unload,
                                           });
      break;
  }
  window_stack_push(s_window, true);
  app_event_loop();
  prv_deinit_ui();

  APP_LOG(APP_LOG_LEVEL_INFO, "main: event loop exited, backbuffer_dirty=%d frontbuffer_dirty=%d",
          s_backbuffer_dirty, s_frontbuffer_dirty);
  if (s_backbuffer_dirty) {
    APP_LOG(APP_LOG_LEVEL_INFO, "main: rearming alarms");
    prv_rearm_alarms(&s_persist, &s_persist_backbuffer);
    APP_LOG(APP_LOG_LEVEL_INFO, "main: writing backbuffer");
    prv_persist_write(&s_persist_backbuffer);
  } else if (s_frontbuffer_dirty) {
    APP_LOG(APP_LOG_LEVEL_INFO, "main: rearming alarms");
    prv_rearm_alarms(&s_persist, &s_persist);
    APP_LOG(APP_LOG_LEVEL_INFO, "main: writing frontbuffer header");
    prv_persist_write_header(&s_persist);
  }

  APP_LOG(APP_LOG_LEVEL_INFO, "main: exiting");
  return 0;
}
