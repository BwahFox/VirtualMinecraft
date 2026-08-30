-- Files: browse the mounts, open a file in Edit, run a program, make, rename, copy, move and delete files,
-- format and burn disks.
--
-- Session 18 ([name]: "the file manager is missing a lot of basic features"): it had no **clipboard** at all, so
-- there was no way to get a file from one directory to another without dropping to the shell -- and no way at
-- all to copy a directory, which is what installing a game off a CD onto the internal disk actually is. Copy /
-- Cut / Paste are buttons now, `fs.copy` does the tree, and the rarer disk jobs (Format, Burn) moved to the
-- right-click menu on the mount they act on, which is both where they belong and how the button row got room.
local app = { id = "files", name = "Files", icon = "/" }

-- One clipboard for the whole machine, not one per window: copying in one Files window and pasting in another
-- is the reason a person opens two of them.
local function clip() return kernel.clipboard end

local SORTS = { "name", "size", "type" }

function app.open(args)
  local T = win.theme
  local wd = win.Window.new{ title = "Files", x = kernel.iconW + 4, y = 12, w = math.min(kernel.w - kernel.iconW - 8, 300), h = math.min(kernel.h - kernel.taskbarH - 18, 190) }
  local r = args.restore or {}
  local path = r.path or "/disk"
  local sort = r.sort or "name"
  local mounts = wd:add(win.List{ items = {} })
  local entries = wd:add(win.List{ items = {} })
  local status = wd:add(win.Label{ text = "" })
  local buttons = {}
  local bh = T.fh + 6
  -- two rows of four: seven labels never fitted one row on a 1x1 monitor, and the second row is now the
  -- clipboard rather than the disk operations
  local rows = { { "Open", "Run", "New", "Rename" }, { "Copy", "Cut", "Paste", "Delete" } }
  for r2, row in ipairs(rows) do
    for c, n in ipairs(row) do buttons[#buttons + 1] = wd:add(win.Button{ text = n, h = bh, row = r2, col = c, cols = #row }) end
  end
  local bottom = bh * #rows + 2 -- the buttons' band
  local mountW = T.fw * 7 + 8
  mounts.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, mountW, ch - bottom - T.fh - 6 end
  entries.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = mountW + 2, 0, cw - mountW - 2, ch - bottom - T.fh - 6 end
  status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, ch - bottom - T.fh - 3, cw - 4, T.fh end
  for _, b in ipairs(buttons) do
    b.layout = function(self, cw, ch)
      local bw = math.floor((cw - 2 * (self.cols - 1)) / self.cols)
      self.x, self.y, self.w, self.h = (self.col - 1) * (bw + 2), ch - bottom + (self.row - 1) * (bh + 2), bw, bh
    end
  end
  wd.buttons, wd.entries, wd.mountsList = buttons, entries, mounts -- the harness

  local items = {}
  local function mountInfo(name)
    for _, m in ipairs(mounts.mountsInfo or {}) do if m.name == name then return m end end
    return nil
  end
  local function here() return path:match("^/([^/]+)") end
  local function readOnly()
    local m = mountInfo(here())
    return m ~= nil and m.readOnly == true
  end

  local function refresh()
    local ok, err = pcall(function()
      local ms = fs.mounts()
      local labels = {}
      for i, m in ipairs(ms) do labels[i] = "/" .. m.name .. (m.foreign and " !" or "") end
      mounts.items = labels
      mounts.mountsInfo = ms
      local mount = here()
      for i, m in ipairs(ms) do if m.name == mount then mounts.selected = i end end
      items = {}
      local list = fs.list(path)
      -- Directories first whatever the sort: a listing that mixes them is a listing you have to read twice.
      table.sort(list, function(a, b)
        if a.dir ~= b.dir then return a.dir end
        if sort == "size" and not a.dir and not b.dir and a.size ~= b.size then return a.size > b.size end
        if sort == "type" then
          local ea, eb = a.name:match("%.([^.]+)$") or "", b.name:match("%.([^.]+)$") or ""
          if ea ~= eb then return ea < eb end
        end
        return a.name < b.name
      end)
      local labels2 = {}
      if path:find("/", 2) then items[1] = { name = "..", dir = true } labels2[1] = ".." end
      for _, e in ipairs(list) do
        items[#items + 1] = e
        labels2[#labels2 + 1] = e.dir and (e.name .. "/") or (e.name .. "  " .. (e.size < 1024 and e.size .. "b" or math.floor(e.size / 1024) .. "k"))
      end
      entries.items = labels2
      entries.selected = nil
      local m = mounts.mountsInfo[mounts.selected or 0]
      -- the mount's label is the disk's own name (what the item says, e.g. `cds:ma1`): say it, so a stack of
      -- floppies is tellable apart from inside the machine (ROADMAP §9 U3, program distribution)
      local label = m and m.label
      if label == nil or label == "" or label == m.name then label = nil end
      status.text = path .. (label and ("  " .. label) or "") .. (m and m.readOnly and "  read-only" or "")
        .. (m and m.quota > 0 and ("   " .. math.floor(m.used / 1024) .. "/" .. math.floor(m.quota / 1024) .. " KB") or "")
      wd.title = "Files " .. path
    end)
    if not ok then status.text = tostring(err) entries.items = {} end
    -- Paste is only offered when there is something to paste into somewhere that will take it
    buttons[7].disabled = clip() == nil or readOnly()
    wd:invalidate()
  end

  local function selected() return entries.selected and items[entries.selected] or nil end
  local function full(e) return fs.join(path, e.name) end

  local function openEntry(e)
    if not e then return end
    if e.dir then
      if e.name == ".." then path = fs.dirname(path) else path = fs.join(path, e.name) end
      refresh()
    else
      -- the start menu's Documents list is "what this machine has been used for", and opening a file here is
      -- the commonest way anything gets used at all
      kernel.addRecent(full(e))
      kernel.open("edit", { path = full(e) })
    end
  end

  ---------------------------------------------------------------------------------------------- the clipboard
  local function setClip(cut)
    local e = selected()
    if not e or e.name == ".." then kernel.notify("Select something first", 3) return end
    kernel.clipboard = { path = full(e), cut = cut and true or false, name = e.name }
    kernel.notify((cut and "Cut " or "Copied ") .. e.name, 2)
    refresh()
  end

  --- A name that is free in `dir`: "thing", then "thing-copy", "thing-copy2", ... The suffix goes before the
  --- extension so a copied program still ends in .lua and still runs, and it is a **hyphen**, not a space:
  --- the filesystem's names are [A-Za-z0-9._-] and a "thing copy" is refused outright (found in the emulator).
  local function freeName(dir, name)
    if not fs.exists(fs.join(dir, name)) then return name end
    local stem, ext = name:match("^(.*)(%.[^.]*)$")
    if not stem then stem, ext = name, "" end
    for n = 1, 99 do
      local try = stem .. "-copy" .. (n > 1 and tostring(n) or "") .. ext
      if #try <= 64 and not fs.exists(fs.join(dir, try)) then return try end
    end
    return nil
  end

  --- Check a name the person typed, and say what the rule is rather than letting the host's error surface.
  local function nameOk(name)
    if fs.validname(name) then return true end
    kernel.notify("Names take " .. fs.NAME_HELP, 5)
    return false
  end

  local function paste()
    local c = clip()
    if not c then return end
    if readOnly() then kernel.notify("/" .. tostring(here()) .. " is read-only", 4) return end
    if not fs.exists(c.path) then kernel.notify(c.name .. " is gone") kernel.clipboard = nil refresh() return end
    local name = freeName(path, c.name)
    if not name then kernel.notify("Too many copies of " .. c.name, 4) return end
    local dst = fs.join(path, name)
    -- a cut inside one mount is a rename, which is instant and keeps a big directory off the wire; anything
    -- else is a real copy, and a cut then removes the original once the copy is safely there
    local ok, err = pcall(function()
      if c.cut and fs.dirname(c.path) ~= path and c.path:match("^/([^/]+)") == here() then
        fs.rename(c.path, dst)
      else
        fs.copy(c.path, dst)
        if c.cut then fs.remove(c.path) end
      end
    end)
    if not ok then kernel.notify(tostring(err), 5) return end
    if c.cut then kernel.clipboard = nil end
    kernel.notify((c.cut and "Moved " or "Copied ") .. name, 2)
    refresh()
  end

  ---------------------------------------------------------------------------------------------- making things
  local function newFile()
    local n = 1
    while fs.exists(fs.join(path, "new" .. n .. ".lua")) do n = n + 1 end
    win.prompt("New file", "Name:", "new" .. n .. ".lua", function(name)
      if not name or name == "" then return end
      if not nameOk(name) then return end
      if fs.exists(fs.join(path, name)) then kernel.notify(name .. " already exists", 3) return end
      kernel.open("edit", { path = fs.join(path, name), text = "-- " .. name .. "\n" })
      refresh()
    end)
  end

  local function newFolder()
    win.prompt("New folder", "Name:", "folder", function(name)
      if not name or name == "" then return end
      if not nameOk(name) then return end
      local ok, err = pcall(fs.mkdir, fs.join(path, name))
      if not ok then kernel.notify(tostring(err), 5) end
      refresh()
    end)
  end

  --- Rename the selected entry (a file or a directory) to `name` in the same directory.
  wd.rename = function(name)
    local e = selected()
    if not e or e.name == ".." then return end
    name = (name or ""):gsub("^%s+", ""):gsub("%s+$", "")
    if name == "" or name == e.name then return end
    if not nameOk(name) then return end
    local ok, err = pcall(fs.rename, full(e), fs.join(path, name))
    if not ok then kernel.notify(tostring(err), 5) end
    refresh()
  end

  local function doRename()
    local e = selected()
    if not e or e.name == ".." then kernel.notify("Select a file or directory first") return end
    win.prompt("Rename", "New name for " .. e.name .. ":", e.name, function(name) if name then wd.rename(name) end end)
  end

  local function doDelete()
    local e = selected()
    if not e or e.name == ".." then return end
    win.ask("Delete", "Delete " .. e.name .. "?", { "Delete", "Cancel" }, function(b)
      if b == "Delete" then
        local ok, err = pcall(fs.remove, full(e))
        if not ok then kernel.notify(tostring(err), 5) end
        refresh()
      end
    end)
  end

  local function properties(e)
    if not e or e.name == ".." then return end
    local p = full(e)
    local st = fs.stat(p) or {}
    local lines = { e.name, p }
    if e.dir then
      local ok, list = pcall(fs.list, p)
      lines[#lines + 1] = "Directory, " .. (ok and #list or "?") .. " entries"
      if fs.exists(fs.join(p, "main.lua")) then lines[#lines + 1] = "A program (main.lua)" end
    else
      lines[#lines + 1] = (st.size or 0) .. " bytes"
    end
    local m = mountInfo(here())
    lines[#lines + 1] = "/" .. tostring(here()) .. (m and m.readOnly and "  read-only" or "  writable")
    win.info("Properties", lines)
  end

  ---------------------------------------------------------------------------------------------- disks
  local function doFormat(mount)
    win.ask("Format", "Erase everything on /" .. mount .. "?", { "Format", "Cancel" }, function(b)
      if b == "Format" then
        local ok, err = pcall(fs.format, mount)
        kernel.notify(ok and ("/" .. mount .. " formatted") or tostring(err), 4)
        path = "/" .. mount
        refresh()
      end
    end)
  end

  local function doBurn(mount)
    local cd
    for _, m in ipairs(mounts.mountsInfo or {}) do if m.name:sub(1, 2) == "cd" and not m.readOnly then cd = m.name end end
    if not cd then kernel.notify("Put a blank CD in a drive first", 4) return end
    win.ask("Burn", "Burn /" .. mount .. " onto /" .. cd .. "?", { "Burn", "Cancel" }, function(b)
      if b == "Burn" then
        local ok, err = pcall(fs.burn, mount, cd)
        kernel.notify(ok and "Burned" or tostring(err), 4)
        refresh()
      end
    end)
  end

  ---------------------------------------------------------------------------------------------- wiring
  mounts.onselect = function(i) path = "/" .. mounts.mountsInfo[i].name refresh() end
  entries.onactivate = function(i) openEntry(items[i]) end
  entries.onselect = function(i) local e = items[i] if e and not e.dir then status.text = full(e) wd:invalidate() end end
  buttons[1].onclick = function() openEntry(selected()) end
  buttons[2].onclick = function()
    local e = selected()
    if e and not e.dir then kernel.runfile(full(e))
    elseif e and e.dir and fs.exists(fs.join(full(e), "main.lua")) then kernel.runfile(fs.join(full(e), "main.lua"))
    else kernel.notify("Select a .lua file or a program directory") end
  end
  buttons[3].onclick = function(b)
    win.menu(wd.x + b.x + 2, wd.y + win.TITLE_H() + b.y + b.h, {
      { text = "File...", onclick = newFile },
      { text = "Folder...", onclick = newFolder },
    })
  end
  buttons[4].onclick = doRename
  buttons[5].onclick = function() setClip(false) end
  buttons[6].onclick = function() setClip(true) end
  buttons[7].onclick = paste
  buttons[8].onclick = doDelete

  --- The right button: a menu about whatever it landed on -- an entry, the empty space below the entries, or a
  --- mount. Everything the buttons cannot fit lives here.
  wd.onrightpress = function(_, lx, ly, px, py)
    if entries:contains(lx, ly) then
      local i = entries:rowAt(ly)
      if i then entries.selected = i wd:invalidate() end
      local e = selected()
      if e and e.name ~= ".." then
        local isProgram = e.dir and fs.exists(fs.join(full(e), "main.lua"))
        win.menu(px, py, {
          { text = e.dir and "Open" or "Open in Edit", onclick = function() openEntry(e) end },
          { text = "Run", disabled = e.dir and not isProgram, onclick = function() buttons[2].onclick() end },
          { sep = true },
          { text = "Copy", onclick = function() setClip(false) end },
          { text = "Cut", disabled = readOnly(), onclick = function() setClip(true) end },
          { text = "Rename...", disabled = readOnly(), onclick = doRename },
          { text = "Delete...", disabled = readOnly(), onclick = doDelete },
          { sep = true },
          { text = "Properties", onclick = function() properties(e) end },
        })
      else
        win.menu(px, py, {
          { text = "New file...", disabled = readOnly(), onclick = newFile },
          { text = "New folder...", disabled = readOnly(), onclick = newFolder },
          { text = clip() and ("Paste " .. clip().name) or "Paste", disabled = clip() == nil or readOnly(), onclick = paste },
          { sep = true },
          { text = "Sort by " .. sort, submenu = function()
            local out = {}
            for _, s in ipairs(SORTS) do
              out[#out + 1] = { text = s, check = s == sort, onclick = function() sort = s refresh() end }
            end
            return out
          end },
          { text = "Refresh", onclick = refresh },
        })
      end
      return true
    end
    if mounts:contains(lx, ly) then
      local i = mounts:rowAt(ly)
      if i then mounts.selected = i mounts.onselect(i) end
      local m = mounts.mountsInfo[mounts.selected or 0]
      if not m then return true end
      win.menu(px, py, {
        { text = "Open /" .. m.name, onclick = function() path = "/" .. m.name refresh() end },
        { sep = true },
        { text = "Format...", disabled = m.readOnly == true or m.name == "rom", onclick = function() doFormat(m.name) end },
        { text = "Burn onto a CD...", disabled = m.name == "rom", onclick = function() doBurn(m.name) end },
        { sep = true },
        { text = "Properties", onclick = function()
          win.info("Properties", { "/" .. m.name, m.label and m.label ~= m.name and m.label or "Internal",
            m.readOnly and "read-only" or "writable",
            m.quota > 0 and (math.floor(m.used / 1024) .. " of " .. math.floor(m.quota / 1024) .. " KB used") or "no quota" })
        end },
      })
      return true
    end
    return false
  end

  wd.onkey = function(_, code, down, mods)
    if not down then return false end
    if code == win.KEY.backspace and path:find("/", 2) then path = fs.dirname(path) refresh() return true end
    if code == win.KEY.delete and not readOnly() then doDelete() return true end
    if code == win.KEY.f5 then refresh() return true end
    if mods.ctrl and code == win.KEY.c then setClip(false) return true end
    if mods.ctrl and code == win.KEY.x then setClip(true) return true end
    if mods.ctrl and code == win.KEY.v then paste() return true end
    return false
  end

  wd.onbus = function(_, ev) if ev.name == "disk_inserted" or ev.name == "disk_ejected" then refresh() end end
  wd.save = function() return { path = path, sort = sort } end
  refresh()
  wd:setfocus(entries)
  return wd
end

function app.save(wd) return wd.save and wd.save() or nil end

return app
