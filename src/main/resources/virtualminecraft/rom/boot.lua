-- The ROM's boot chunk (ROADMAP §7h §7): the bare machine hands us vmc.* and nothing else. Load the kernel from
-- /rom and run it. A disk with its own boot.lua boots instead of this file (§2) and sees the same vmc.*.
local src = vmc.fs_read("/rom/kernel.lua")
local kernel, err = load(src, "=kernel.lua", "t", _G)
if not kernel then error("ROM kernel failed to load: " .. tostring(err), 0) end
kernel()
