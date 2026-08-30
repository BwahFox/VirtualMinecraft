-- Terminal: the shell in a window (ROADMAP §9 U2) — a prompt that shows where you are, Linux-flavoured verbs,
-- programs by name, Tab completion, colour, and the Lua REPL one `=` away. Output from print() anywhere on the
-- machine lands here too. The harness's `exec` event types Lua into it; `shell` types a shell line.
local app = { id = "terminal", name = "Terminal", icon = ">" }

local history = {}
local cwd = nil

function app.open(args)
  local T = win.theme
  local wd = win.Window.new{ title = "Terminal", x = kernel.iconW, y = 6, w = math.min(kernel.w - kernel.iconW - 4, 320), h = math.min(kernel.h - kernel.taskbarH - 12, 200) }
  wd.minW, wd.minH = 120, 60
  if kernel.console then wd.borderless = true end -- the shell *is* the screen on a Basic Computer
  local out = wd:add(win.TextArea{ readonly = true, wrap = true, maxlines = 300, bg = 0, fg = 7 })
  local promptLabel = wd:add(win.Label{ text = "$", fg = T.ok })
  local input = wd:add(win.TextField{ text = "" })
  local sh = shell.new{
    print = function(text, fg) out:append(text, fg) end,
    clear = function() out:settext("") end,
    cols = function() return out:cols() end,
  }
  sh.history = history
  sh.tee = function(text) vmc.log(1, text) end -- the shell's output also reaches the machine's console (/vmc computer state, the harness)
  if args.restore and args.restore.history then history = args.restore.history sh.history = history end
  if args.restore and args.restore.cwd then cwd = args.restore.cwd end
  if cwd and fs.isdir(cwd) then sh.cwd = cwd end
  local function prompt()
    promptLabel.text = sh:prompt()
    promptLabel.fg = sh.mode == "lua" and T.accent or T.ok
    wd:relayout()
  end
  out.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, cw, ch - T.fh - 6 end
  promptLabel.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, ch - T.fh - 3, win.textw(self.text or "") + 2, T.fh end
  input.layout = function(self, cw, ch)
    local pw = win.textw(promptLabel.text or "") + 4
    self.x, self.y, self.w, self.h = pw, ch - T.fh - 6, cw - pw, T.fh + 6
  end
  prompt()
  for _, l in ipairs(kernel.log) do out:append(l) end
  local histPos = #history + 1
  local function runLine(text)
    history[#history + 1] = text
    if #history > 100 then table.remove(history, 1) end
    sh:run(text)
    cwd = sh.cwd
    prompt()
  end
  input.onenter = function(text)
    input.text = ""
    input.cursor = 0
    if text ~= "" then
      out:append(sh:prompt() .. " " .. text, T.disabled)
      histPos = #history + 2
      runLine(text)
    end
  end
  local baseKey = input.key
  input.key = function(self, code, down, mods)
    if down and code == win.KEY.tab then
      local line, cands = sh:complete(self.text or "")
      if #cands > 1 then
        out:append(table.concat(cands, "  "), T.disabled)
      end
      self.text = line
      self.cursor = #line
      self:invalidate()
      return true
    end
    if down and (code == win.KEY.up or code == win.KEY.down) then
      histPos = math.max(1, math.min(#history + 1, histPos + (code == win.KEY.up and -1 or 1)))
      self.text = history[histPos] or ""
      self.cursor = #self.text
      self:invalidate()
      return true
    end
    return baseKey(self, code, down, mods)
  end
  wd:setfocus(input)
  kernel.onlog = function(text) if not wd.closed then out:append(text) end end
  wd.onclose = function() if kernel.onlog then kernel.onlog = nil end end
  -- the harness / a command: Lua straight into the REPL, or a shell line with its output teed to the console log
  local function teed(fn, arg)
    local ok, err = pcall(fn, arg)
    if not ok then out:append(tostring(err), T.warn) end
  end
  wd.exec = function(code) out:append("= " .. code, T.disabled) teed(function(c) sh:lua(c) end, code) end
  wd.shell = function(line) out:append(sh:prompt() .. " " .. line, T.disabled) teed(runLine, line) end
  wd.complete = function(line) return (sh:complete(line)) end -- the harness
  wd.sh = sh
  wd.onbus = function(_, ev)
    if ev.name == "redstone_changed" then out:append("(redstone " .. tostring(ev.side) .. " " .. tostring(ev.previous) .. " -> " .. tostring(ev.level) .. ")", T.disabled)
    elseif ev.name == "chat" then out:append("<" .. tostring(ev.player) .. "> " .. tostring(ev.message), T.accent)
    elseif ev.name == "net_message" then
      local msg = type(ev.message) == "table" and json.encode(ev.message) or tostring(ev.message)
      out:append("<" .. tostring(ev.sender) .. "> " .. msg, T.accent)
      vmc.log(1, "<" .. tostring(ev.sender) .. "> " .. msg) -- the machine console too (/vmc computer state, the harness)
    elseif ev.name == "disk_inserted" or ev.name == "disk_ejected" then out:append("(" .. ev.name .. " " .. tostring(ev.description) .. ")", T.disabled)
    elseif ev.name == "component_added" or ev.name == "component_removed" then out:append("(" .. ev.name .. " " .. tostring(ev.type) .. "@" .. tostring(ev.location) .. ")", T.disabled)
    end
  end
  return wd
end

function app.save(wd) return { history = history, cwd = cwd } end

return app
