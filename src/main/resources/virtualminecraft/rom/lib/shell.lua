-- The shell (ROADMAP §9 U2): a Linux-flavoured command line that happens to be scriptable in Lua. Plain Lua over
-- fs / kernel / bus; the Terminal window is its front-end, `sh:run(line)` its entry, the harness drives it through
-- the `shell` event. Deliberately no pipes, no &&, no grep — the feel of a terminal, not POSIX. Every command has a
-- usage line and a paragraph for `help <cmd>`; `=<expr>` and `lua` keep the Lua REPL as the escape hatch.
local shell = {}
local commands = {}
local order = {}
shell.commands = commands
local T = win and win.theme or { text = 7, warn = 8, sel = 12, ok = 11, accent = 9, disabled = 5 }

---------------------------------------------------------------------------------------------------- paths
--- The shell speaks relative paths and `..`; the filesystem refuses them, so everything is normalised here.
function shell.normalize(path)
  local parts = {}
  for seg in path:gmatch("[^/]+") do
    if seg == ".." then if #parts > 0 then table.remove(parts) end
    elseif seg ~= "." then parts[#parts + 1] = seg end
  end
  return "/" .. table.concat(parts, "/")
end

--- Where "home" is. Normally /disk -- but a case with no drive in it has no /disk at all (ROADMAP §9 U10(a)),
--- and the shell still has to start somewhere it can list. First choice the internal disk, then any writable
--- removable, then the ROM, which is read-only but always there.
function shell.home()
  if fs.exists("/disk") then return "/disk" end
  for _, m in ipairs(fs.mounts()) do
    if not m.readOnly and not m.foreign then return "/" .. m.name end
  end
  return "/rom"
end

local function resolve(sh, path)
  if path == nil or path == "" then return sh.cwd end
  if path:sub(1, 1) == "~" then path = (sh.vars and sh.vars.HOME or "/disk") .. path:sub(2) end
  if path:sub(1, 1) ~= "/" then path = sh.cwd .. "/" .. path end
  return shell.normalize(path)
end
shell.resolve = resolve

--- Directory entries as {name, dir, size}; "/" lists the mounts.
local function list(path)
  if path == "/" then
    local out = {}
    for _, m in ipairs(fs.mounts()) do out[#out + 1] = { name = m.name, dir = true, size = 0, mount = m } end
    return out
  end
  return fs.list(path)
end
shell.list = list

local function isdir(path) return path == "/" or fs.isdir(path) end
local function mountOf(path) return path:match("^/([^/]+)") end

---------------------------------------------------------------------------------------------------- words
--- Split a line into words: whitespace-separated, "double" and 'single' quotes group, # starts a comment.
function shell.split(line)
  local words, cur, quote, any = {}, {}, nil, false
  local i = 1
  while i <= #line do
    local c = line:sub(i, i)
    if quote then
      if c == quote then quote = nil else cur[#cur + 1] = c end
    elseif c == '"' or c == "'" then quote = c any = true
    elseif c == "#" and #cur == 0 and not any then break
    elseif c:match("%s") then
      if #cur > 0 or any then words[#words + 1] = table.concat(cur) cur = {} any = false end
    else cur[#cur + 1] = c end
    i = i + 1
  end
  if #cur > 0 or any then words[#words + 1] = table.concat(cur) end
  return words
end

--- $name, ${name}, $1..$9, $* and $0 from the session's variables (scripts set the numbered ones).
function shell.expand(sh, line, vars)
  local v = vars or sh.vars
  return (line:gsub("%$(%b{})", function(b) local k = b:sub(2, -2) return tostring(v[k] or sh.vars[k] or "") end)
    :gsub("%$([%w_%*]+)", function(k) return tostring(v[k] or sh.vars[k] or "") end))
end

---------------------------------------------------------------------------------------------------- session
--- A session: `out` is {print = function(text, fg), clear = function(), cols = function() -> n}.
function shell.new(out)
  local home = shell.home()
  local sh = { out = out, cwd = home, vars = { PATH = home .. "/bin:" .. home, HOME = home }, history = {}, mode = nil, depth = 0 }
  sh.luaenv = setmetatable({}, { __index = _G, __newindex = _G })
  function sh.luaenv.help() sh:print("This is the Lua prompt. `exit` returns to the shell; `help` there lists the commands.") sh:print("bus.list()  bus.call(target, method, ...)  net.*  fs.*  gfx.*  snd.*  os.*  state.*  kernel.*") end
  return setmetatable(sh, { __index = shell })
end

function shell.print(sh, text, fg)
  for l in (tostring(text) .. "\n"):gmatch("([^\n]*)\n") do
    sh.out.print(l, fg)
    if sh.tee then sh.tee(l) end
  end
end
function shell.err(sh, text) sh:print(text, T.warn) end
function shell.prompt(sh) return sh.mode == "lua" and "lua>" or (sh.cwd .. " $") end

--- $PATH as a list: the cwd first, then PATH's entries, then the removable mounts.
local function searchPath(sh)
  local dirs = { sh.cwd }
  for d in (sh.vars.PATH or ""):gmatch("[^:]+") do dirs[#dirs + 1] = d end
  for _, m in ipairs(fs.mounts()) do
    if m.name ~= "rom" and m.name ~= "disk" then dirs[#dirs + 1] = "/" .. m.name end
  end
  return dirs
end

--- Find a program by name: a path, or name / name.lua / name.sh / name/main.lua in the search path.
function shell.find(sh, name)
  local function stat(p) local ok, st = pcall(fs.stat, p) return ok and st or nil end -- a bad name is "not found", not an error
  local function probe(base)
    local st = stat(base)
    if st and not st.dir then
      -- a main.lua beside a program.txt is a program (full-screen), like the launcher treats it
      if fs.basename(base) == "main.lua" and fs.exists(fs.join(fs.dirname(base), "program.txt")) then return { path = base, kind = "program" } end
      return { path = base, kind = base:match("%.sh$") and "sh" or "lua" }
    end
    if st and st.dir and fs.exists(fs.join(base, "main.lua")) then return { path = fs.join(base, "main.lua"), kind = "program" } end
    for _, ext in ipairs({ ".lua", ".sh" }) do
      local p = base .. ext
      local s2 = stat(p)
      if s2 and not s2.dir then return { path = p, kind = ext == ".sh" and "sh" or "lua" } end
    end
    return nil
  end
  if name:find("/") then return probe(resolve(sh, name)) end
  for _, dir in ipairs(searchPath(sh)) do
    local found = probe(fs.join(dir, name))
    if found then return found end
  end
  -- a program the launcher knows by its program.txt name (a game CD: `2048`)
  for _, p in ipairs(kernel.diskPrograms()) do
    if p.name:lower() == name:lower() then return { path = p.path, kind = "program" } end
  end
  return nil
end

--- Run a found program: a .lua file inline (its print lands here; args arrive as ...), a .sh line by line, a
--- program directory / CD as a full-screen program.
function shell.exec(sh, prog, args)
  if prog.kind == "sh" then return shell.script(sh, prog.path, args) end
  if prog.kind == "program" then kernel.runfile(prog.path, args) return end
  local results = table.pack(pcall(fs.run, prog.path, table.unpack(args)))
  if not results[1] then sh:err(tostring(results[2])) return end
  for i = 2, results.n do if results[i] ~= nil then sh:print(tostring(results[i])) end end
end

function shell.script(sh, path, args)
  if sh.depth >= 8 then sh:err("scripts nested too deep") return end
  local src = fs.read(path)
  local vars = setmetatable({ ["0"] = path, ["*"] = table.concat(args, " ") }, { __index = sh.vars })
  for i, a in ipairs(args) do vars[tostring(i)] = a end
  sh.depth = sh.depth + 1
  for l in (src .. "\n"):gmatch("([^\n]*)\n") do
    l = l:gsub("\r$", "")
    if l:match("^%s*$") == nil and l:match("^%s*#") == nil then
      sh:run(l, vars)
      if sh.stopped then break end
    end
  end
  sh.depth = sh.depth - 1
  sh.stopped = nil
end

--- The Lua escape hatch: an expression or a chunk in the REPL's environment.
function shell.lua(sh, src)
  local fn, err = load("return " .. src, "=lua", "t", sh.luaenv)
  if not fn then fn, err = load(src, "=lua", "t", sh.luaenv) end
  if not fn then sh:err(err) return end
  local results = table.pack(pcall(fn))
  if not results[1] then sh:err(tostring(results[2])) return end
  if results.n > 1 then
    local parts = {}
    for i = 2, results.n do parts[#parts + 1] = tostring(results[i]) end
    sh:print(table.concat(parts, "\t"))
  end
end

--- One line. `vars` is a script's $1..$9 (nil for a typed line).
function shell.run(sh, line, vars)
  line = line:match("^%s*(.-)%s*$")
  if line == "" then return end
  if sh.mode == "lua" then
    if line == "exit" then sh.mode = nil return end
    return shell.lua(sh, line)
  end
  if line:sub(1, 1) == "=" then return shell.lua(sh, line:sub(2)) end
  local first, rest = line:match("^(%S+)%s*(.*)$")
  if first == "lua" and rest ~= "" then return shell.lua(sh, rest) end -- Lua keeps its quotes: not split, not expanded
  local words = shell.split(shell.expand(sh, line, vars))
  if #words == 0 then return end
  local name = table.remove(words, 1)
  local cmd = commands[name]
  if cmd then
    local ok, err = pcall(cmd.run, sh, words, name)
    if not ok then sh:err(tostring(err)) end
    return
  end
  local prog = shell.find(sh, name)
  if prog then return shell.exec(sh, prog, words) end
  sh:err(name .. ": command not found  (help lists the commands; =<expr> or lua for Lua)")
end

---------------------------------------------------------------------------------------------------- completion
local function commonPrefix(list)
  local p = list[1] or ""
  for i = 2, #list do
    local s = list[i]
    local n = 0
    while n < #p and n < #s and p:sub(n + 1, n + 1) == s:sub(n + 1, n + 1) do n = n + 1 end
    p = p:sub(1, n)
  end
  return p
end

--- Tab: complete the last word of `line` — a command or program name first, a path anywhere else. Returns the
--- new line and the candidates (more than one when the prefix was ambiguous; the front-end shows them).
function shell.complete(sh, line)
  local before, word = line:match("^(.*%s)(%S*)$")
  if not before then before, word = "", line end
  local first = before:match("^%s*$") ~= nil
  local cands = {}
  if first then
    if sh.mode == "lua" then return line, {} end
    for _, name in ipairs(order) do if name:sub(1, #word) == word then cands[#cands + 1] = name .. " " end end
    for _, dir in ipairs(searchPath(sh)) do
      local ok, entries = pcall(list, dir)
      if ok then
        for _, e in ipairs(entries) do
          local n = e.name:gsub("%.lua$", ""):gsub("%.sh$", "")
          if n:sub(1, #word) == word and (e.dir or e.name ~= n) then cands[#cands + 1] = n .. " " end
        end
      end
    end
  else
    local dir, part = word:match("^(.*/)([^/]*)$")
    if not dir then dir, part = "", word end
    local ok, entries = pcall(list, resolve(sh, dir == "" and "." or dir))
    if ok then
      for _, e in ipairs(entries) do
        if e.name:sub(1, #part) == part then cands[#cands + 1] = dir .. e.name .. (e.dir and "/" or " ") end
      end
    end
  end
  local seen, uniq = {}, {}
  for _, c in ipairs(cands) do if not seen[c] then seen[c] = true uniq[#uniq + 1] = c end end
  table.sort(uniq)
  if #uniq == 0 then return line, uniq end
  if #uniq == 1 then return before .. uniq[1], uniq end
  return before .. commonPrefix(uniq), uniq
end

---------------------------------------------------------------------------------------------------- commands
local function cmd(name, usage, help, run) commands[name] = { usage = usage, help = help, run = run } order[#order + 1] = name end
local function alias(name, target) commands[name] = commands[target] end

local function columns(sh, items, colorOf)
  local width = 0
  for _, it in ipairs(items) do width = math.max(width, #it) end
  local cols = math.max(1, math.floor((sh.out.cols and sh.out.cols() or 40) / (width + 2)))
  local row, rowColor = {}, nil
  for i, it in ipairs(items) do
    row[#row + 1] = it .. string.rep(" ", width + 2 - #it)
    if #row == cols or i == #items then
      -- one colour per line: coloured only when every item on the line agrees, else the default
      local c = colorOf and colorOf(items[i - #row + 1])
      for j = 2, #row do if colorOf and colorOf(items[i - #row + j]) ~= c then c = nil end end
      sh:print(table.concat(row), c)
      row = {}
    end
  end
end

local function entryColor(e)
  if e.dir then return T.sel end
  if e.name:match("%.lua$") or e.name:match("%.sh$") then return T.ok end
  return nil
end

cmd("ls", "ls [path] [-l]", "List a directory: directories in blue with a trailing /, programs (.lua, .sh) in green. -l adds sizes, one entry per line. `ls /` lists the mounts.", function(sh, args)
  local long, target = false, nil
  for _, a in ipairs(args) do if a == "-l" then long = true else target = a end end
  local path = resolve(sh, target)
  if not isdir(path) then
    if fs.exists(path) then sh:print(fs.basename(path)) return end
    error(tostring(target or path) .. ": no such directory", 0)
  end
  local entries = list(path)
  table.sort(entries, function(a, b) if a.dir ~= b.dir then return a.dir end return a.name < b.name end)
  if #entries == 0 then return end
  if long then
    for _, e in ipairs(entries) do
      local size = e.dir and "<dir>" or (e.size >= 10240 and (math.floor(e.size / 1024) .. " KB") or (tostring(e.size) .. " B"))
      local label = e.mount and (e.mount.label .. (e.mount.readOnly and "  ro" or "")) or ""
      sh:print(string.format("%-20s %9s  %s", e.name .. (e.dir and "/" or ""), size, label), entryColor(e))
    end
  else
    local names, byName = {}, {}
    for _, e in ipairs(entries) do local n = e.name .. (e.dir and "/" or "") names[#names + 1] = n byName[n] = e end
    columns(sh, names, function(n) return entryColor(byName[n]) end)
  end
end)
alias("dir", "ls")

cmd("cd", "cd [path]", "Change the working directory (no argument: /disk; `cd -` goes back; `..` goes up; `~` is /disk). The prompt shows where you are.", function(sh, args)
  local target = args[1]
  if target == "-" then target = sh.prev or sh.cwd end
  local path = target and resolve(sh, target) or sh.vars.HOME
  if not isdir(path) then error(tostring(args[1]) .. ": no such directory", 0) end
  sh.prev = sh.cwd
  sh.cwd = path
end)

cmd("pwd", "pwd", "Print the working directory.", function(sh) sh:print(sh.cwd) end)

cmd("cat", "cat <file> [file...]", "Print a file (the first 200 lines).", function(sh, args)
  if #args == 0 then error("cat: which file?", 0) end
  for _, a in ipairs(args) do
    local path = resolve(sh, a)
    if isdir(path) then error(a .. ": is a directory", 0) end
    local text = fs.read(path)
    local n = 0
    for l in (text .. "\n"):gmatch("([^\n]*)\n") do
      n = n + 1
      if n <= 200 then sh:print(l) end
    end
    if n > 201 then sh:print("(" .. (n - 201) .. " more lines)", T.disabled) end
  end
end)

local function copyInto(sh, src, dst, recursive)
  local s, d = resolve(sh, src), resolve(sh, dst)
  if isdir(d) then d = fs.join(d, fs.basename(s)) end
  if isdir(s) and not recursive then error(src .. ": is a directory (cp -r)", 0) end
  fs.copy(s, d)
  return s, d
end

cmd("cp", "cp <src> <dst> [-r]", "Copy a file; a directory needs -r. A directory as the destination keeps the name. Works across mounts.", function(sh, args)
  local recursive, rest = false, {}
  for _, a in ipairs(args) do if a == "-r" then recursive = true else rest[#rest + 1] = a end end
  if #rest < 2 then error("cp: cp <src> <dst> [-r]", 0) end
  copyInto(sh, rest[1], rest[2], recursive)
end)

cmd("mv", "mv <src> <dst>", "Move or rename a file or directory; across mounts a file is copied and the original removed.", function(sh, args)
  if #args < 2 then error("mv: mv <src> <dst>", 0) end
  local s, d = resolve(sh, args[1]), resolve(sh, args[2])
  if isdir(d) then d = fs.join(d, fs.basename(s)) end
  local ok = pcall(fs.rename, s, d)
  if not ok then
    if isdir(s) then error("mv: cannot move a directory across mounts", 0) end
    fs.write(d, fs.read(s))
    fs.remove(s)
  end
end)

cmd("rm", "rm <path> [-r]", "Remove a file; a directory needs -r. Mount roots stay.", function(sh, args)
  local recursive, target = false, nil
  for _, a in ipairs(args) do if a == "-r" or a == "-rf" then recursive = true else target = a end end
  if not target then error("rm: rm <path> [-r]", 0) end
  local path = resolve(sh, target)
  if path == "/" or path:match("^/[^/]+$") then error("rm: " .. target .. " is a mount", 0) end
  if not fs.exists(path) then error(target .. ": no such file", 0) end
  if isdir(path) and not recursive then error(target .. ": is a directory (rm -r)", 0) end
  fs.remove(path)
end)

cmd("mkdir", "mkdir <path>", "Make a directory.", function(sh, args)
  if not args[1] then error("mkdir: mkdir <path>", 0) end
  fs.mkdir(resolve(sh, args[1]))
end)

cmd("touch", "touch <file>", "Make an empty file (an existing one is left alone).", function(sh, args)
  if not args[1] then error("touch: touch <file>", 0) end
  local path = resolve(sh, args[1])
  if not fs.exists(path) then fs.write(path, "") end
end)

cmd("clear", "clear", "Clear the screen.", function(sh) if sh.out.clear then sh.out.clear() end end)
alias("cls", "clear")

cmd("echo", "echo [words...]", "Print the words. $NAME, $1.. and $* are expanded first (see set).", function(sh, args) sh:print(table.concat(args, " ")) end)

cmd("set", "set [NAME value]", "Show the variables, or set one: `set NAME hello` then `echo $NAME`. PATH is where programs are looked for (colon-separated).", function(sh, args)
  if not args[1] then
    local keys = {}
    for k in pairs(sh.vars) do keys[#keys + 1] = k end
    table.sort(keys)
    for _, k in ipairs(keys) do sh:print(k .. "=" .. tostring(sh.vars[k])) end
    return
  end
  sh.vars[args[1]] = table.concat(args, " ", 2)
end)

cmd("run", "run <program> [args...]", "Run a program: a .lua file runs here with its output in this window and the arguments as ... ; a .sh script runs line by line; a directory with main.lua (or a game CD) opens full-screen. Programs are found in the working directory, $PATH and the removable disks — so `run foo` finds /disk/foo.lua, and so does plain `foo`.", function(sh, args)
  if not args[1] then error("run: run <program> [args...]", 0) end
  local prog = shell.find(sh, args[1])
  if not prog then error(args[1] .. ": no such program", 0) end
  shell.exec(sh, prog, { table.unpack(args, 2) })
end)

cmd("start", "start <file.lua> [args...]", "Open a Lua file as a full-screen program (a game), whatever its name. The arguments reach it as `...`.", function(sh, args)
  if not args[1] then error("start: start <file.lua> [args...]", 0) end
  local prog = shell.find(sh, args[1])
  if not prog then error(args[1] .. ": no such program", 0) end
  kernel.runfile(prog.path, { table.unpack(args, 2) })
end)

cmd("sh", "sh <script.sh> [args...]", "Run a shell script: one command per line, # comments, $1..$9 the arguments, $* all of them, $0 the script. `exit` stops it.", function(sh, args)
  if not args[1] then error("sh: sh <script> [args...]", 0) end
  local prog = shell.find(sh, args[1])
  if not prog then error(args[1] .. ": no such script", 0) end
  shell.script(sh, prog.path, { table.unpack(args, 2) })
end)

cmd("edit", "edit <file>", "Open a file in Edit (F5 there saves and runs it).", function(sh, args)
  if not args[1] then error("edit: edit <file>", 0) end
  kernel.open("edit", { path = resolve(sh, args[1]) })
end)

cmd("open", "open <app>", "Open an app by its id (apps lists them): open paint.", function(sh, args)
  if not args[1] then error("open: open <app>", 0) end
  local wd = kernel.find(args[1])
  if wd then kernel.focus(wd) return end
  if not kernel.apps[args[1]] then error(args[1] .. ": no such app (apps lists them)", 0) end
  kernel.open(args[1])
end)

cmd("apps", "apps", "List the apps and the programs on the disks (what the Apps button shows).", function(sh)
  for _, id in ipairs(kernel.order) do
    local app = kernel.apps[id]
    if not app.hidden then sh:print(string.format("%-12s %s", id, app.name)) end
  end
  for _, p in ipairs(kernel.diskPrograms()) do sh:print(string.format("%-12s %s", p.name, p.path), T.ok) end
end)

local function programState(p)
  if not p.alive then return "dead" end
  if p.wake > 0 then return "sleeping" end
  if p.waiting then return "waiting" end
  return "running"
end

cmd("ps", "ps", "List the running programs: number, state, name, window.", function(sh)
  sh:print(string.format("%3s  %-9s %-14s %s", "#", "state", "name", "window"), T.disabled)
  for i, p in ipairs(kernel.programs) do
    sh:print(string.format("%3d  %-9s %-14s %s", i, programState(p), p.name, p.window and p.window.title or ""))
  end
end)

cmd("kill", "kill <#|name>", "Stop a program (see ps) and close its window.", function(sh, args)
  if not args[1] then error("kill: kill <#|name>", 0) end
  local n = tonumber(args[1])
  for i, p in ipairs(kernel.programs) do
    if i == n or p.name == args[1] then
      if p.window then kernel.close(p.window) else kernel.kill(p) end
      sh:print("killed " .. p.name)
      return
    end
  end
  error(args[1] .. ": no such program", 0)
end)

cmd("top", "top", "The machine at a glance: memory, uptime, screen, programs, windows.", function(sh)
  local used, cap = vmc.mem()
  local w, h = gfx.size()
  local case = os.info()
  sh:print(string.format("case    %s   cpu %d%%   %d colours   disk %d KB   sound %d+%d", case.tierName or "Computer", case.cpu or 25, case.colours or 256, case.disk or 0, case.synth or 4, case.samples or 2))
  sh:print(string.format("memory  %d / %d KB used", math.floor(used / 1024), math.floor(cap / 1024)))
  sh:print(string.format("uptime  %d s   world day %d %s", math.floor(os.clock()), select(2, os.date()), (os.date())))
  sh:print(string.format("screen  %dx%d   frame %d ms", w, h, vmc.frame_ms and vmc.frame_ms() or 50))
  sh:print(string.format("programs %d   windows %d", #kernel.programs, #kernel.windows))
end)

cmd("df", "df", "The mounts with their use and quota.", function(sh)
  for _, m in ipairs(fs.mounts()) do
    local q = m.quota > 0 and string.format("%d / %d KB", math.floor(m.used / 1024), math.floor(m.quota / 1024)) or ""
    sh:print(string.format("/%-6s %-16s %-4s %s", m.name, m.label, m.readOnly and "ro" or "rw", q .. (m.foreign and "  FOREIGN" or "")))
  end
end)
alias("mounts", "df")

cmd("history", "history", "The lines typed so far (Up/Down walk them).", function(sh)
  for i, l in ipairs(sh.history) do sh:print(string.format("%4d  %s", i, l)) end
end)

cmd("net", "net [send <to> <text...> | all <text...>]", "The other computers this one can reach: next to it, on its bus cable, or -- with a wireless modem on both buses -- within radio range (shown as `wireless`). `net send <name|address> <text>` sends them a line, `net all <text>` sends it to every one; what arrives shows in the Terminal as `<name> text` (the net_message event; net.* in Lua).", function(sh, args)
  if not args[1] then
    local ok, peers = pcall(net.list)
    if not ok then return sh:err("net: " .. tostring(peers)) end
    if #peers == 0 then return sh:print("no other computer within reach (cable, or a modem on both)", T.disabled) end
    for _, p in ipairs(peers) do sh:print(string.format("%-16s %s  %s", tostring(p.name), tostring(p.address), tostring(p.location))) end
  elseif args[1] == "send" and args[2] and args[3] then
    local ok, err = pcall(net.send, args[2], table.concat(args, " ", 3))
    if not ok then sh:err("net: " .. tostring(err)) end
  elseif args[1] == "all" and args[2] then
    local ok, n = pcall(net.broadcast, table.concat(args, " ", 2))
    if not ok then sh:err("net: " .. tostring(n)) else sh:print("sent to " .. tostring(n) .. " computer(s)", T.disabled) end
  else sh:err("usage: " .. commands.net.usage) end
end)

-- Making something and then using it (ROADMAP §9 U3): Music writes /disk/songs/<name>.json and Paint writes
-- /disk/sprites/<name>.spr, and these two commands are the shell's half of that loop.
local playing
cmd("play", "play <file> | play stop", "Play a song saved by Music (a .json file: it loops until `play stop`) or a raw 8-bit sample (any other file, on a sample channel). `examples song` does the same with a picture of the song; snd.playsong does it from your own program.", function(sh, args)
  if args[1] == "stop" or args[1] == "off" then
    if playing then playing.stop() playing = nil end
    snd.stop()
    return sh:print("stopped", T.disabled)
  end
  if not args[1] then error("play: " .. commands.play.usage, 0) end
  local path = resolve(sh, args[1])
  if not fs.exists(path) then error(path .. ": no such file", 0) end
  if playing then playing.stop() playing = nil end
  if path:match("%.json$") then
    playing = snd.playsong(path)
    sh:print("playing " .. path .. " (play stop ends it)", T.disabled)
  else
    local data = fs.read(path)
    snd.sample(1, data, 22050)
    snd.play(5, 1, 1, false)
    sh:print(string.format("%s: %d bytes at 22050 Hz", path, #data), T.disabled)
  end
end)

cmd("examples", "examples [name [args...]]", "The example programs in /rom/examples: without a name, list them; with one, run it full-screen. They are small and meant to be copied and changed (`cp /rom/examples/song.lua /disk/mysong.lua`, then `edit` it).", function(sh, args)
  if not args[1] then
    for _, e in ipairs(fs.list("/rom/examples")) do
      if not e.dir then
        local name = e.name:gsub("%.lua$", "")
        local first = (fs.read("/rom/examples/" .. e.name):match("^%-%-%s*([^\r\n]*)")) or ""
        -- no colour: T.text is 0, black on the Terminal's black, which is why this listing used to come out blank
        sh:print(string.format("%-8s %s", name, first))
      end
    end
    return sh:print("examples <name> runs one; they are files in /rom/examples", T.disabled)
  end
  local path = "/rom/examples/" .. args[1]:gsub("%.lua$", "") .. ".lua"
  if not fs.exists(path) then error(args[1] .. ": no such example (try `examples`)", 0) end
  kernel.runfile(path, { table.unpack(args, 2) })
end)

cmd("man", "man [page]", "The manual: without an argument, list its pages; with a number or a word from a title, print that page here. The Manual app is the same text with a window round it, and is nicer to read.", function(sh, args)
  local dir = "/rom/manual"
  local pages = {}
  local ok, entries = pcall(fs.list, dir)
  if not ok then error("the manual is missing from " .. dir, 0) end
  table.sort(entries, function(a, b) return a.name < b.name end)
  for _, e in ipairs(entries) do
    if not e.dir and e.name:match("%.txt$") then
      local body = fs.read(dir .. "/" .. e.name)
      pages[#pages + 1] = { name = e.name, title = body:match("^([^\n]*)") or e.name, body = body }
    end
  end
  local want = args[1] and table.concat(args, " "):lower() or nil
  if not want then
    for i, p in ipairs(pages) do sh:print(string.format("%2d  %s", i, p.title)) end
    sh:print("man <number> or man <word> to read one; man <command> for that command", T.disabled)
    return
  end
  -- `man` was an alias for `help` before the manual existed, and `man ls` is the older habit of the two: if the
  -- word names a command, answer as help would rather than telling somebody they are holding it wrong.
  local asCommand = commands[want]
  if asCommand then
    sh:print(asCommand.usage, T.ok)
    sh:print(asCommand.help)
    return
  end
  local hit = pages[tonumber(want) or 0]
  if not hit then
    for _, p in ipairs(pages) do if p.title:lower():find(want, 1, true) then hit = p break end end
  end
  if not hit then error("no page or command like '" .. want .. "' (try `man` or `help`)", 0) end
  for l in (hit.body .. "\n"):gmatch("([^\n]*)\n") do
    for _, wrapped in ipairs(win.wrap(l, sh.out.cols and sh.out.cols() or 40)) do sh:print(wrapped) end
  end
end)

cmd("date", "date [format]", "The world's date and time. World tick 0 is 1970-01-01 06:00 (ROADMAP §9 U10(b)), so a world that has been played a while has a real date; `date %Y-%m-%d` takes any of os.date's formats.", function(sh, args)
  if args[1] then sh:print(os.date(table.concat(args, " "))) return end
  local t, d = os.date()
  sh:print(os.date("%A %d %B %Y") .. "  " .. t)
  sh:print("Minecraft day " .. d .. "   epoch " .. math.floor(os.epoch()))
end)

-- The terminal counterparts of the world apps (ROADMAP §9 U3b): a Basic Computer has no desktop, so Redstone,
-- Inventory and World must be reachable from the prompt. They speak to the same components the apps do.
local SIDES = { "front", "back", "left", "right", "top", "bottom" }
local function relativeSides()
  -- getInputs/getOutputs answer by absolute side; map the six relative names from getFacing, like the Redstone app
  local OPP = { north = "south", south = "north", east = "west", west = "east" }
  local CW = { north = "east", east = "south", south = "west", west = "north" }
  local CCW = { north = "west", west = "south", south = "east", east = "north" }
  local abs = { front = "north", back = "south", left = "west", right = "east", top = "up", bottom = "down" }
  local ok, facing = pcall(bus.call, "redstone", "getFacing")
  if ok and type(facing) == "string" and OPP[facing] then
    abs.front, abs.back, abs.left, abs.right = facing, OPP[facing], CCW[facing], CW[facing]
  end
  return abs
end
cmd("rs", "rs [on|off <side> | set <side> <0-15> | wake <0-15> | sleep on|off]", "Redstone: the six sides with their input and output levels. `rs on right` / `rs off right` / `rs set right 7` drive an output. `rs wake 5` powers this computer on when any side rises to 5 (0 = never); `rs sleep on` shuts it down again when they fall back below. Sides: front back left right top bottom (or north south east west up down).", function(sh, args)
  local sub = args[1]
  if sub == "wake" then
    local n = tonumber(args[2] or "")
    if not n then sh:print("usage: rs wake <0-15>   (0 = never wake on redstone)", win.theme.warn) return end
    local ok, err = pcall(bus.call, "redstone", "setWake", math.floor(n))
    if ok then sh:print(n > 0 and ("wake at " .. math.floor(n)) or "redstone wake off") else sh:print(tostring(err), win.theme.warn) end
    return
  end
  if sub == "sleep" then
    local on = args[2] == "on" or args[2] == "true"
    if args[2] ~= "on" and args[2] ~= "off" and args[2] ~= "true" and args[2] ~= "false" then
      sh:print("usage: rs sleep on|off", win.theme.warn) return
    end
    local ok, err = pcall(bus.call, "redstone", "setSleep", on)
    if ok then sh:print("redstone sleep " .. (on and "on" or "off")) else sh:print(tostring(err), win.theme.warn) end
    return
  end
  if sub == "on" or sub == "off" or sub == "set" then
    local side = args[2]
    local level = sub == "on" and 15 or sub == "off" and 0 or tonumber(args[3] or "")
    if not side or not level then sh:print("usage: rs on|off <side>  |  rs set <side> <0-15>", win.theme.warn) return end
    local ok, err = pcall(bus.call, "redstone", "setOutput", side, level)
    if ok then sh:print(side .. " = " .. level) else sh:print(tostring(err), win.theme.warn) end
    return
  end
  local ok, err = pcall(function()
    local abs = relativeSides()
    local ins = bus.call("redstone", "getInputs") or {}
    local outs = bus.call("redstone", "getOutputs") or {}
    sh:print("side      in  out")
    for _, side in ipairs(SIDES) do
      local a = abs[side]
      sh:print(string.format("%-8s %3d  %3d   %s", side, tonumber(ins[a] or ins[side]) or 0, tonumber(outs[a] or outs[side]) or 0, a))
    end
    local wake = tonumber(bus.call("redstone", "getWake")) or 0
    sh:print(wake > 0 and ("wake at " .. wake .. ", sleep " .. (bus.call("redstone", "getSleep") and "on" or "off")) or "wake off")
  end)
  if not ok then sh:print(tostring(err), win.theme.warn) end
end)

cmd("inv", "inv [<n> | move <from> <slot> <to> [count]]", "The containers on the bus: alone, list them numbered; `inv 1` lists what is in the first; `inv move 1 3 2` pushes slot 3 of the first into the second (`[count]` items, all by default).", function(sh, args)
  local comps = {}
  for _, c in ipairs(bus.list()) do if c.type == "inventory" then comps[#comps + 1] = c end end
  local function pick(x)
    local n = tonumber(x or "")
    if n and comps[n] then return comps[n] end
    for _, c in ipairs(comps) do if c.address == x or tostring(c.location) == x then return c end end
  end
  if args[1] == "move" then
    local from, slot, to, count = pick(args[2]), tonumber(args[3] or ""), pick(args[4]), tonumber(args[5] or "")
    if not (from and slot and to) then sh:print("usage: inv move <from> <slot> <to> [count]", win.theme.warn) return end
    local ok, moved = pcall(bus.call, from.address, "pushItems", to.address, slot, count)
    if ok then sh:print("moved " .. tostring(moved)) else sh:print(tostring(moved), win.theme.warn) end
    return
  end
  if args[1] then
    local c = pick(args[1])
    if not c then sh:print("no such inventory: " .. args[1], win.theme.warn) return end
    local ok, err = pcall(function()
      local list = bus.call(c.address, "list") or {}
      local keys = {}
      for k in pairs(list) do keys[#keys + 1] = tonumber(k) end
      table.sort(keys)
      for _, k in ipairs(keys) do
        local it = list[tostring(k)] or list[k]
        sh:print(string.format("%3d  %3d  %s", k, it.count or 0, it.displayName or it.name or "?"))
      end
      if #keys == 0 then sh:print("(empty)") end
    end)
    if not ok then sh:print(tostring(err), win.theme.warn) end
    return
  end
  if #comps == 0 then sh:print("no inventory on the bus") return end
  for i, c in ipairs(comps) do
    local okn, n = pcall(bus.call, c.address, "name")
    local oks, sz = pcall(bus.call, c.address, "size")
    sh:print(string.format("%2d  %-16s %s slots  @%s", i, okn and tostring(n) or "?", oks and tostring(sz) or "?", tostring(c.location)))
  end
end)

cmd("world", "world", "Where this computer is and what it sees: position, day and time, weather, biome, the players near.", function(sh)
  local ok, err = pcall(function()
    local p = bus.call("world", "getPosition") or {}
    local t = bus.call("world", "getTime") or {}
    local w = bus.call("world", "getWeather") or {}
    local biome = tostring(bus.call("world", "getBiome") or "?")
    local dim = tostring(p.dimension or "")
    sh:print(string.format("at %s, %s, %s  %s", tostring(p.x), tostring(p.y), tostring(p.z), (dim:gsub("^minecraft:", ""))))
    -- The WORLD's clock, not the wall clock. Minecraft time 0 is 06:00, and a day is 24000 ticks, so an hour
    -- is 1000. This printed os.date() -- the real time on [name]'s actual computer -- under a "day N" label,
    -- which is exactly as wrong as it sounds.
    local ticks = tonumber(t.time) or 0
    local hh = math.floor((ticks / 1000 + 6) % 24)
    local mm = math.floor((ticks % 1000) / 1000 * 60)
    sh:print(string.format("day %s  %02d:%02d%s", tostring(t.day), hh, mm, t.daylight and "  daylight" or "  night"))
    sh:print("weather " .. tostring(w.weather) .. (w.rainingHere and "  (raining here)" or ""))
    sh:print("biome " .. (biome:gsub("^minecraft:", "")))
    local ps = bus.call("world", "getPlayers") or {}
    local names = {}
    for i, pl in ipairs(ps) do names[i] = string.format("%s %.0f m", tostring(pl.name), tonumber(pl.distance) or 0) end
    sh:print("players " .. (#names > 0 and table.concat(names, ", ") or "(nobody near)"))
  end)
  if not ok then sh:print(tostring(err), win.theme.warn) end
end)

cmd("palette", "palette [<index> [rrggbb]]", "The screen's colours: alone, reset them; with an index, show that colour; with a colour too, set it (above the case's colour count it stays the default).", function(sh, args)
  if not args[1] then gfx.palette() kernel.invalidate() sh:print("palette reset") return end
  local i = tonumber(args[1])
  if not i or i < 0 or i > 255 then sh:print("usage: palette [<0-255> [rrggbb]]", win.theme.warn) return end
  if args[2] then
    local rgb = tonumber(args[2], 16)
    if not rgb then sh:print("colour as six hex digits, e.g. ff8800", win.theme.warn) return end
    gfx.palette(i, rgb)
    kernel.invalidate()
  end
  sh:print(string.format("%d = %06x", i, gfx.palette(i)))
end)

cmd("label", "label [name]", "Show or set this computer's name (the one on the block and in chat).", function(sh, args)
  if args[1] then os.label(table.concat(args, " ")) end
  sh:print(os.label())
end)

cmd("lua", "lua [code]", "Lua: with code, run it once; alone, switch the prompt to Lua until `exit`. `=<expr>` evaluates an expression from the shell prompt. The Lua prompt has bus, fs, gfx, snd, os, state and kernel in scope.", function(sh, args)
  if #args > 0 then return shell.lua(sh, table.concat(args, " ")) end
  sh.mode = "lua"
  sh:print("Lua prompt. `exit` returns to the shell.", T.disabled)
end)

cmd("exit", "exit", "Leave the Lua prompt, or stop a running script.", function(sh) sh.stopped = true end)

cmd("reboot", "reboot", "Reboot this computer (the desktop comes back as it was).", function() kernel.save() os.reboot() end)
cmd("shutdown", "shutdown", "Shut this computer down.", function() kernel.save() os.shutdown() end)

cmd("help", "help [command]", "Without an argument, every command in a line each; with one, its usage and what it does. `man` is the same.", function(sh, args)
  if args[1] then
    local c = commands[args[1]]
    if not c then error(args[1] .. ": no such command", 0) end
    sh:print(c.usage, T.ok)
    sh:print(c.help)
    return
  end
  local cols = sh.out.cols and sh.out.cols() or 40
  if cols < 44 then
    columns(sh, order) -- a narrow window: the names alone, in columns
  else
    for _, name in ipairs(order) do
      local c = commands[name]
      sh:print(string.format("%-9s %s", name, c.help:match("^[^.]*")), nil)
    end
  end
  sh:print("help <command> says more. Programs on the disks run by name; =<expr> or lua for Lua.", T.disabled)
end)
-- `man` used to be an alias for `help` and is the manual now (ROADMAP §9 U3c). It still answers `man ls` with
-- the help for ls, so nobody's habit breaks -- see the command itself.
alias("?", "help")

-- run(path, ...) at the Lua prompt, as the first Terminal had it (Edit's F5 and the harness use it)
if fs and _G.run == nil then _G.run = fs.run end

return shell
