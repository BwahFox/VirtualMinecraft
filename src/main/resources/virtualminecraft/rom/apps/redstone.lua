-- Redstone: the six sides — inputs as they change, outputs by button, a name per side (ROADMAP §7h §7).
local app = { id = "redstone", name = "Redstone", icon = "R" }
local SIDES = { "front", "back", "left", "right", "top", "bottom" }

function app.open(args)
  local T = win.theme
  local r = args.restore or {}
  local names = r.names or {}
  local rowH = T.fh + 8
  local wd = win.Window.new{ title = "Redstone", x = kernel.iconW + 6, y = 14, w = math.min(kernel.w - kernel.iconW - 10, 300), h = math.min(kernel.h - kernel.taskbarH - 20, win.TITLE_H() + rowH * 7 + 6) }
  wd.minW, wd.minH = wd.w, wd.h -- fixed rows
  local nameW = T.fw * 8 + 4
  local numW = T.fw * 3
  local btnW = T.fw * 3 + 4
  wd:add(win.Label{ x = 4, y = 2, w = nameW, text = "side" })
  wd:add(win.Label{ x = nameW + 6, y = 2, w = numW, text = "in" })
  wd:add(win.Label{ x = nameW + numW + 8, y = 2, w = numW, text = "out" })
  local inputs, outputs = {}, {}
  -- getInputs/getOutputs answer by *absolute* side (north…down); the rows are relative, so map them once from
  -- getFacing (front = facing, right = its clockwise neighbour, like the component's own parser).
  local OPP = { north = "south", south = "north", east = "west", west = "east" }
  local CW = { north = "east", east = "south", south = "west", west = "north" }
  local CCW = { north = "west", west = "south", south = "east", east = "north" }
  local abs = { front = "north", back = "south", left = "west", right = "east", top = "up", bottom = "down" }
  do
    local ok, facing = pcall(bus.call, "redstone", "getFacing")
    if ok and type(facing) == "string" and OPP[facing] then
      abs.front, abs.back, abs.left, abs.right = facing, OPP[facing], CCW[facing], CW[facing]
    end
  end
  local function set(side, level)
    local ok, err = pcall(bus.call, "redstone", "setOutput", side, level)
    if not ok then kernel.notify(tostring(err), 4) end
  end
  local function refresh()
    local ok, err = pcall(function()
      local ins = bus.call("redstone", "getInputs") or {}
      local outs = bus.call("redstone", "getOutputs") or {}
      for _, side in ipairs(SIDES) do
        local i, o = tonumber(ins[abs[side]] or ins[side]) or 0, tonumber(outs[abs[side]] or outs[side]) or 0
        inputs[side].text = tostring(i)
        inputs[side].fg = i > 0 and T.accent or T.text
        outputs[side].text = tostring(o)
        outputs[side].fg = o > 0 and T.accent or T.text
      end
    end)
    if not ok then kernel.notify(tostring(err), 4) end
    wd:invalidate()
  end
  for i, side in ipairs(SIDES) do
    local y = 2 + i * rowH
    local nameBtn = wd:add(win.Button{ x = 2, y = y, w = nameW, h = rowH - 2, text = names[side] or side })
    nameBtn.onclick = function()
      win.prompt("Name", "Name for " .. side, names[side] or side, function(t)
        if t == nil then return end
        if t == "" or t == side then names[side] = nil else names[side] = t end
        nameBtn.text = names[side] or side
        wd:invalidate()
      end)
    end
    inputs[side] = wd:add(win.Label{ x = nameW + 6, y = y + 4, w = numW, text = "-" })
    outputs[side] = wd:add(win.Label{ x = nameW + numW + 8, y = y + 4, w = numW, text = "-" })
    local bx = nameW + numW * 2 + 10
    wd:add(win.Button{ x = bx, y = y, w = btnW, h = rowH - 2, text = "Off", side = side, onclick = function() set(side, 0) refresh() end })
    wd:add(win.Button{ x = bx + btnW + 2, y = y, w = btnW, h = rowH - 2, text = "On", side = side, onclick = function() set(side, 15) refresh() end })
  end
  wd.onbus = function(_, ev) if ev.name == "redstone_changed" then refresh() end end
  wd.save = function() return { names = names } end
  wd.set = set -- the harness
  refresh()
  -- outputs apply on the next server tick and inputs change without an event when we power a block ourselves
  kernel.spawn("redstone", function() while not wd.closed do os.sleep(1000) if not wd.closed then refresh() end end end, wd)
  return wd
end

function app.save(wd) return wd.save and wd.save() or nil end

return app
