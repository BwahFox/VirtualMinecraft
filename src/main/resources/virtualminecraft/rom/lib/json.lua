-- json.encode / json.decode: the bus and the state files speak JSON. Pure Lua, no dependencies.
local json = {}
local escapes = { ['"'] = '\\"', ['\\'] = '\\\\', ['\b'] = '\\b', ['\f'] = '\\f', ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t' }

local function is_array(t)
  local n = 0
  for k in pairs(t) do
    if type(k) ~= "number" or k <= 0 or k % 1 ~= 0 then return false end
    n = n + 1
  end
  return n == #t
end

local function enc(v, out)
  local t = type(v)
  if t == "nil" then out[#out + 1] = "null"
  elseif t == "boolean" then out[#out + 1] = tostring(v)
  elseif t == "number" then
    if v ~= v or v == math.huge or v == -math.huge then out[#out + 1] = "null"
    elseif v % 1 == 0 and math.abs(v) < 2^53 then out[#out + 1] = string.format("%d", v)
    else out[#out + 1] = string.format("%.17g", v) end
  elseif t == "string" then out[#out + 1] = '"' .. v:gsub('[%c"\\]', function(c) return escapes[c] or string.format("\\u%04x", c:byte()) end) .. '"'
  elseif t == "table" then
    if is_array(v) then
      out[#out + 1] = "["
      for i = 1, #v do if i > 1 then out[#out + 1] = "," end enc(v[i], out) end
      out[#out + 1] = "]"
    else
      out[#out + 1] = "{"
      local first = true
      for k, x in pairs(v) do
        if not first then out[#out + 1] = "," end
        first = false
        enc(tostring(k), out); out[#out + 1] = ":"; enc(x, out)
      end
      out[#out + 1] = "}"
    end
  else error("cannot encode " .. t) end
end

function json.encode(v) local out = {} enc(v, out) return table.concat(out) end

local dec
-- Strings are copied a run at a time (everything up to the next quote or backslash), not a character at a time: a
-- 47 KB file decoded byte by byte churned ~8 MB of one-character strings, enough to trip the machine's per-slice
-- allocation check on a 4 MB Computer.
local function decstr(s, i)
  local out, j = {}, i + 1
  while true do
    local k = s:find('["\\]', j)
    if not k then error("unterminated string") end
    if k > j then out[#out + 1] = s:sub(j, k - 1) end
    j = k
    local c = s:sub(j, j)
    if c == '"' then return table.concat(out), j + 1 end
    if c == "\\" then
      local e = s:sub(j + 1, j + 1)
      local map = { b = "\b", f = "\f", n = "\n", r = "\r", t = "\t", ['"'] = '"', ["\\"] = "\\", ["/"] = "/" }
      if e == "u" then
        local cp = tonumber(s:sub(j + 2, j + 5), 16)
        if cp < 0x80 then out[#out + 1] = string.char(cp)
        elseif cp < 0x800 then out[#out + 1] = string.char(0xC0 + math.floor(cp / 64), 0x80 + cp % 64)
        else out[#out + 1] = string.char(0xE0 + math.floor(cp / 4096), 0x80 + math.floor(cp / 64) % 64, 0x80 + cp % 64) end
        j = j + 6
      else out[#out + 1] = map[e] or e; j = j + 2 end
    end
  end
end

function dec(s, i)
  local _, e = s:find("^[ \n\r\t]*", i); i = e + 1
  local c = s:sub(i, i)
  if c == "{" then
    local t = {}; i = i + 1
    _, e = s:find("^[ \n\r\t]*", i); i = e + 1
    if s:sub(i, i) == "}" then return t, i + 1 end
    while true do
      _, e = s:find("^[ \n\r\t]*", i); i = e + 1
      local k; k, i = decstr(s, i)
      _, e = s:find("^[ \n\r\t]*:[ \n\r\t]*", i); i = e + 1
      t[k], i = dec(s, i)
      _, e = s:find("^[ \n\r\t]*", i); i = e + 1
      local d = s:sub(i, i); i = i + 1
      if d == "}" then return t, i end
      if d ~= "," then error("expected , or } at " .. i) end
    end
  elseif c == "[" then
    local t = {}; i = i + 1
    _, e = s:find("^[ \n\r\t]*", i); i = e + 1
    if s:sub(i, i) == "]" then return t, i + 1 end
    while true do
      t[#t + 1], i = dec(s, i)
      _, e = s:find("^[ \n\r\t]*", i); i = e + 1
      local d = s:sub(i, i); i = i + 1
      if d == "]" then return t, i end
      if d ~= "," then error("expected , or ] at " .. i) end
    end
  elseif c == '"' then return decstr(s, i)
  elseif s:sub(i, i + 3) == "true" then return true, i + 4
  elseif s:sub(i, i + 4) == "false" then return false, i + 5
  elseif s:sub(i, i + 3) == "null" then return nil, i + 4
  else
    local num = s:match("^-?%d+%.?%d*[eE]?[-+]?%d*", i)
    if not num or num == "" then error("unexpected character at " .. i .. ": " .. c) end
    return tonumber(num), i + #num
  end
end

function json.decode(s) local v = dec(s, 1) return v end

return json
