-- Inventory: every container on the bus, search, move stacks between them by pointer (ROADMAP §7h §7).
local app = { id = "inventory", name = "Inventory", icon = "#" }

function app.open(args)
  local T = win.theme
  local r = args.restore or {}
  local wd = win.Window.new{ title = "Inventory", x = kernel.iconW + 6, y = 14, w = math.min(kernel.w - kernel.iconW - 10, 320), h = math.min(kernel.h - kernel.taskbarH - 20, 200) }
  local invs = wd:add(win.List{ items = {} })     -- left: the inventories on the bus
  local search = wd:add(win.TextField{ text = r.search or "" })
  local items = wd:add(win.List{ items = {} })    -- right: the selected one's stacks
  local status = wd:add(win.Label{ text = "" })
  local bh = T.fh + 6
  local buttons = {}
  for i, n in ipairs({ "Move 1", "Move all", "Target", "Refresh" }) do buttons[i] = wd:add(win.Button{ text = n, h = bh }) end
  local leftW = T.fw * 9 + 8
  invs.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, leftW, ch - bh - T.fh - 6 end
  search.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = leftW + 2, 0, cw - leftW - 2, T.fh + 6 end
  items.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = leftW + 2, T.fh + 8, cw - leftW - 2, ch - bh - T.fh - 6 - (T.fh + 8) end
  status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, ch - bh - T.fh - 3, cw - 4, T.fh end
  for i, b in ipairs(buttons) do
    b.layout = function(self, cw, ch)
      local bw = math.floor((cw - 2 * (#buttons - 1)) / #buttons)
      self.x, self.y, self.w, self.h = (i - 1) * (bw + 2), ch - bh, bw, bh
    end
  end
  wd:relayout()

  local comps, slots = {}, {}
  local target = r.target -- address of the inventory stacks move to
  local function byAddress(a) for _, c in ipairs(comps) do if c.address == a then return c end end end
  local function source() return comps[invs.selected or 0] end

  local function refreshList()
    local ok, err = pcall(function()
      local found = {}
      for _, c in ipairs(bus.list()) do if c.type == "inventory" then found[#found + 1] = c end end
      local labels = {}
      for i, c in ipairs(found) do
        local old = byAddress(c.address)
        c.label = old and old.label
        if not c.label then
          local okn, n = pcall(bus.call, c.address, "name")
          c.label = (okn and type(n) == "string" and n ~= "") and n or c.type
        end
        labels[i] = c.label .. " @" .. tostring(c.location)
      end
      local keep = source() and source().address
      comps = found
      invs.items = labels
      invs.selected = nil
      for i, c in ipairs(comps) do if c.address == keep then invs.selected = i end end
      if not invs.selected and #comps > 0 then invs.selected = 1 end
      if target and not byAddress(target) then target = nil end
    end)
    if not ok then status.text = tostring(err) end
  end

  local function refreshItems()
    slots = {}
    local c = source()
    if not c then items.items = {} status.text = "No inventory on the bus" wd:invalidate() return end
    local ok, err = pcall(function()
      local list = bus.call(c.address, "list")
      local size = bus.call(c.address, "size")
      local keys = {}
      for k in pairs(list or {}) do keys[#keys + 1] = tonumber(k) end
      table.sort(keys)
      local q = (search.text or ""):lower()
      local rows = {}
      for _, k in ipairs(keys) do
        local it = list[tostring(k)] or list[k]
        local shown = it.displayName or it.name or "?"
        if q == "" or shown:lower():find(q, 1, true) or tostring(it.name or ""):lower():find(q, 1, true) then
          slots[#slots + 1] = k
          rows[#rows + 1] = string.format("%3d %s", it.count or 0, shown)
        end
      end
      items.items = rows
      local t = target and byAddress(target)
      status.text = #keys .. "/" .. tostring(size) .. " slots" .. (t and ("  > " .. t.label .. " @" .. tostring(t.location)) or "  (no target)")
    end)
    if not ok then items.items = {} status.text = tostring(err) end
    if items.selected and items.selected > #slots then items.selected = nil end
    wd:invalidate()
  end
  local function refresh() refreshList() refreshItems() end

  local function move(all)
    local c = source()
    local slot = items.selected and slots[items.selected]
    if not c or not slot then kernel.notify("Select a stack first", 3) return end
    if not target then kernel.notify("Pick a target with the Target button", 3) return end
    -- "all" means *leave the count off* so the component moves the whole stack. This cannot be written as
    -- `all and nil or 1`: `and nil` collapses and the `or` branch always wins, so Move all quietly moved a
    -- single item -- and a trailing nil in a vararg is ambiguous anyway. Two calls, one argument apart.
    local ok, res
    if all then ok, res = pcall(bus.call, c.address, "pushItems", target, slot)
    else ok, res = pcall(bus.call, c.address, "pushItems", target, slot, 1) end
    if not ok then kernel.notify(tostring(res), 4) else kernel.notify("Moved " .. tostring(res), 2) end
    refreshItems()
  end
  wd.move = move -- the harness calls it
  buttons[1].onclick = function() move(false) end
  buttons[2].onclick = function() move(true) end
  buttons[3].onclick = function()
    -- cycle the target through the other inventories
    if #comps < 2 then kernel.notify("Two inventories are needed to move", 3) return end
    local i = 0
    for j, c in ipairs(comps) do if c.address == target then i = j end end
    for _ = 1, #comps do
      i = i % #comps + 1
      if comps[i] ~= source() then target = comps[i].address break end
    end
    refreshItems()
  end
  buttons[4].onclick = refresh
  invs.onselect = function() refreshItems() end
  items.onactivate = function() move(true) end
  search.onchange = function() refreshItems() end
  search.onenter = function() refreshItems() end
  wd.onbus = function(_, ev)
    if ev.name == "component_added" or ev.name == "component_removed" then refresh() end
  end
  wd.save = function() return { search = search.text, target = target } end
  refresh()
  wd:setfocus(items)
  kernel.spawn("inventory", function() while not wd.closed do os.sleep(5000) if not wd.closed then refreshItems() end end end, wd)
  return wd
end

function app.save(wd) return wd.save and wd.save() or nil end

return app
