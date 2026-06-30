// build.zig
const std = @import("std");

const pebble_sdk = @import("pebble_sdk");

pub fn build(b: *std.Build) !void {
    pebble_sdk.addPebbleApplication(b, .{
        .name = "calendar-notifications",
        .pebble = .{
            .displayName = "Calendar Notifications",
            .author = "Eliza",
            .uuid = "075a861e-c60b-4bb6-b3f2-b592925e86b1",
            .version = .{ .major = 0, .minor = 1 },
            .targetPlatforms = &.{ .emery, .gabbro },
          },
        .root_source_file = b.path("src/main.zig"),
        .optimize = .ReleaseSmall,
    });
}
