// src/main.zig
const std = @import("std");

const pebble = @import("pebble");

var s_window: ?*pebble.Window = null;
var s_text_layer: ?*pebble.TextLayer = null;

fn window_load(window: ?*pebble.Window) callconv(.c) void {
    const window_layer = pebble.window_get_root_layer(window);
    const bounds = pebble.layer_get_bounds(window_layer);

    s_text_layer = pebble.text_layer_create(.{
        .origin = .{ .x = 0, .y = @divTrunc(bounds.size.h, 2) - 25 },
        .size = .{ .w = bounds.size.w, .h = 50 },
    });
    pebble.text_layer_set_font(s_text_layer, pebble.fonts_get_system_font(pebble.FONT_KEY_GOTHIC_28_BOLD));
    pebble.text_layer_set_text_color(s_text_layer, pebble.GColorBlue);
    pebble.text_layer_set_text_alignment(s_text_layer, pebble.GTextAlignmentCenter);
    pebble.text_layer_set_text(s_text_layer, "Hello World!");

    pebble.layer_add_child(window_layer, pebble.text_layer_get_layer(s_text_layer));
}

fn window_unload(_: ?*pebble.Window) callconv(.c) void {
    pebble.text_layer_destroy(s_text_layer);
}

fn init() void {
    s_window = pebble.window_create();
    pebble.window_set_window_handlers(s_window, .{
        .load = window_load,
        .unload = window_unload,
    });
    pebble.window_stack_push(s_window, true);
}

fn deinit() void {
    pebble.window_destroy(s_window);
}

export fn main() void {
    init();
    pebble.app_event_loop();
    deinit();
}
