// build.zig
const std = @import("std");

const pebble_sdk = @import("pebble_sdk");

fn disableBuildId(step: *std.Build.Step, visited: *std.AutoHashMap(*std.Build.Step, void)) void {
    if (visited.contains(step)) return;
    visited.put(step, {}) catch return;

    if (step.cast(std.Build.Step.Compile)) |compile| {
        compile.build_id = .none;
    }
    for (step.dependencies.items) |dep| {
        disableBuildId(dep, visited);
    }
}

pub fn build(b: *std.Build) !void {
    pebble_sdk.addPebbleApplication(b, .{
        .name = "calnotify",
        .pebble = .{
            .displayName = "Calendar Notifications",
            .author = "Eliza",
            .uuid = "075a861e-c60b-4bb6-b3f2-b592925e86b1",
            .version = .{ .major = 0, .minor = 1 },
            .targetPlatforms = &.{ .emery, .gabbro },
            .companionApp = .{
                .android = .{
                    .url = "https://github.com/TODO",
                    .apps = &.{
                        .{ .package = "sh.eliza.pebble.calnotify" },
                    },
                },
            },
          },
        .root_source_file = b.path("src/main.zig"),
        .optimize = .ReleaseSmall,
    });

    var visited = std.AutoHashMap(*std.Build.Step, void).init(b.allocator);
    defer visited.deinit();

    disableBuildId(b.getInstallStep(), &visited);
    for (b.top_level_steps.values()) |tls| {
        disableBuildId(&tls.step, &visited);
    }
}
