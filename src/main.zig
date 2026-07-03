// src/main.zig
const std = @import("std");
const pebble = @import("pebble");

var s_window: ?*pebble.Window = null;
var s_text_layer: ?*pebble.TextLayer = null;
var s_buffer: [64]u8 = undefined;

fn send_message(value: u8) void {
    var out_iter: ?*pebble.DictionaryIterator = null;
    const result = pebble.app_message_outbox_begin(&out_iter);
    if (result == pebble.APP_MSG_OK) {
        _ = pebble.dict_write_uint8(out_iter, 1, value);
        _ = pebble.app_message_outbox_send();
    }
}

fn prv_select_click_handler(_: pebble.ClickRecognizerRef, _: ?*anyopaque) callconv(.c) void {
    send_message(1);
}

fn prv_up_click_handler(_: pebble.ClickRecognizerRef, _: ?*anyopaque) callconv(.c) void {
    send_message(0);
}

fn prv_down_click_handler(_: pebble.ClickRecognizerRef, _: ?*anyopaque) callconv(.c) void {
    send_message(2);
}

fn prv_click_config_provider(_: ?*anyopaque) callconv(.c) void {
    pebble.window_single_click_subscribe(pebble.BUTTON_ID_SELECT, prv_select_click_handler);
    pebble.window_single_click_subscribe(pebble.BUTTON_ID_UP, prv_up_click_handler);
    pebble.window_single_click_subscribe(pebble.BUTTON_ID_DOWN, prv_down_click_handler);
}

fn prv_window_load(window: ?*pebble.Window) callconv(.c) void {
    const window_layer = pebble.window_get_root_layer(window);
    const bounds = pebble.layer_get_bounds(window_layer);

    s_text_layer = pebble.text_layer_create(.{
        .origin = .{ .x = 0, .y = 72 },
        .size = .{ .w = bounds.size.w, .h = 20 },
    });
    pebble.text_layer_set_text(s_text_layer, "Press a button");
    pebble.text_layer_set_text_alignment(s_text_layer, pebble.GTextAlignmentCenter);
    pebble.layer_add_child(window_layer, pebble.text_layer_get_layer(s_text_layer));
}

fn prv_window_unload(_: ?*pebble.Window) callconv(.c) void {
    pebble.text_layer_destroy(s_text_layer);
}

fn prv_init() void {
    s_window = pebble.window_create();
    pebble.window_set_click_config_provider(s_window, prv_click_config_provider);
    pebble.window_set_window_handlers(s_window, .{
        .load = prv_window_load,
        .unload = prv_window_unload,
    });
    pebble.window_stack_push(s_window, true);
}

fn prv_deinit() void {
    pebble.window_destroy(s_window);
}

fn inbox_received_callback(iter: ?*pebble.DictionaryIterator, _: ?*anyopaque) callconv(.c) void {
    const string_tuple = pebble.dict_find(iter, 1);
    if (string_tuple) |tuple| {
        // The value data follows immediately after the Tuple struct header (7 bytes)
        const tuple_ptr = @as([*c]u8, @ptrCast(tuple));
        const text = tuple_ptr + 7;
        _ = std.fmt.bufPrintZ(&s_buffer, "{s}", .{text}) catch return;
        pebble.text_layer_set_text(s_text_layer, @ptrCast(&s_buffer));
    }
}

export fn main() void {
    prv_init();

    _ = pebble.app_message_open(64, 64);
    _ = pebble.app_message_register_inbox_received(inbox_received_callback);

    pebble.app_event_loop();
    prv_deinit();
}
