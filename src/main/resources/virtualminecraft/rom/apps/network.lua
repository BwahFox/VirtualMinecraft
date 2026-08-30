-- Network: who this computer can reach, and whether they answer.
--
-- The mod has had the substance for a while — the `net` component, modem blocks, the cable bus — and no way at
-- all to look at it. You placed a second modem and nothing anywhere told you it had worked. This is that
-- window: every machine within reach, how it is reached (a cable side, an offset, or the radio), and whether it
-- is awake enough to reply.
--
-- "Awake enough to reply" is worth explaining, because it is the only clever thing here. There is no ping in the
-- protocol and adding one would mean every program having to answer it. Instead this sends the same `get` the
-- Browser sends; anything running the Server answers with a page, and anything else stays quiet. So the list
-- says "serving" for machines you can actually browse, and "reachable" for ones that are merely there.
local app = { id = "network", name = "Network", icon = "N" }

local PROTO = "vw1"
local TIMEOUT = 3

function app.open(args)
  local T = win.theme
  local wd = win.Window.new{ title = "Network", x = kernel.iconW + 6, y = 14,
    w = math.min(kernel.w - kernel.iconW - 10, 320),
    h = math.min(kernel.h - kernel.taskbarH - 20, 210) }

  local rows = {}          -- { address, name, location, state }
  local pinged, pingAt = {}, 0
  local self = wd:add(win.Label{ x = 4, y = 2, w = 100, text = "" })
  self.layout = function(s2, cw, ch) s2.w = math.max(20, cw - T.fw * 11) end
  local list = wd:add(win.List{ x = 2, y = T.fh + 6, w = 10, h = 10, items = {} })
  list.layout = function(s, cw, ch)
    s.x, s.y, s.w, s.h = 2, T.fh + 6, cw - 4, ch - (T.fh + 6) - (T.fh + 10)
  end
  local status = wd:add(win.Label{ x = 4, y = 0, w = 400, text = "" })
  status.layout = function(s, cw, ch) s.x, s.y, s.w = 4, ch - T.fh - 2, cw - 8 end

  -- Padded columns get cut off on a narrow window before the interesting part (the state) is ever reached, so
  -- the state leads and the name is what gets trimmed if anything must be.
  local function label(r)
    -- "z" is a machine whose chunk is not loaded (U9): still addressable, and a send wakes it, but it cannot
    -- answer a ping until it is awake -- so without this mark it reads as broken when it is merely asleep.
    local mark = r.state == "serving" and "*" or (r.loaded == false and "z" or "-")
    return mark .. " " .. win.fit(r.name or "?", T.fw * 16) .. "  " .. tostring(r.location or "?")
  end

  local function relabel()
    local items = {}
    for i, r in ipairs(rows) do items[i] = label(r) end
    list.items = items
    wd:invalidate()
  end

  -- One sweep: ask the component who is out there, then poke each of them once.
  local function scan()
    local ok, peers = pcall(bus.call, "net", "list")
    if not ok or type(peers) ~= "table" then
      rows = {}
      relabel()
      status.text = "No net component: it cannot reach anything."
      self.text = "add a modem or a cable"
      return
    end
    local addr = select(2, pcall(bus.call, "net", "address"))
    self.text = "this: " .. win.fit(tostring(addr or "?"), math.max(20, wd.w - T.fw * 18))
    rows = {}
    local note = nil
    local asleep = 0
    for _, p in ipairs(peers) do
      if p.note then
        -- the component telling us the cable run hit its length cap, so a short list is explained rather than
        -- looking like a gap in the wire (U9)
        note = tostring(p.note)
      else
        if p.loaded == false then asleep = asleep + 1 end
        rows[#rows + 1] = { address = tostring(p.address or "?"), name = tostring(p.name or p.address or "?"),
                            location = tostring(p.location or "?"), state = "reachable", loaded = p.loaded ~= false }
      end
    end
    relabel()
    if #rows == 0 then
      status.text = note or "Nothing in reach: needs a cable or a modem on each."
      return
    end
    -- Poke the ones that are awake, and let the replies land in onbus. **Sleeping peers are not poked**: a send
    -- wakes a machine (U9), and opening this window must not drag every chunk on the run into memory to find
    -- out what is on it. You can still wake one deliberately by opening it from the list.
    pinged, pingAt = {}, os.clock()
    local awake = 0
    for _, r in ipairs(rows) do
      if r.loaded then
        awake = awake + 1
        pcall(bus.call, "net", "send", r.address, { p = PROTO, op = "get", path = "/" })
      end
    end
    if note then
      status.text = note
    elseif asleep > 0 then
      status.text = awake .. " awake, " .. asleep .. " asleep (z) -- a send wakes those"
    else
      status.text = awake .. " reachable, asking who serves..."
    end
    wd:invalidate()
  end

  -- A page coming back means that machine is running the Server, which is the only thing worth knowing beyond
  -- "it exists". Anything else it says is ignored: this window is not a browser.
  wd.onbus = function(_, ev)
    if ev.name == "net_message" then
      local m = ev.message
      if type(m) == "table" and m.p == PROTO and m.op == "page" then
        for _, r in ipairs(rows) do
          if r.address == tostring(ev.from) or r.name == tostring(ev.sender) then
            r.state = "serving"
            pinged[r.address] = true
          end
        end
        relabel()
      end
    elseif ev.name == "net_peers" or ev.name == "modem_changed" then
      scan()
    end
  end

  list.onactivate = function(i)
    local r = rows[i]
    if not r then return end
    kernel.notify(r.name .. "  " .. r.location .. "  " .. r.address, 6)
  end

  wd:add(win.Button{ x = 2, y = 2, w = T.fw * 8, h = T.fh + 4, text = "Rescan" }).layout = function(s, cw, ch)
    s.x, s.y = cw - T.fw * 9, 0
  end
  wd.widgets[#wd.widgets].onclick = scan

  scan()
  -- The sweep has no end signal — a machine that is switched off simply never answers — so after the timeout
  -- whatever has not replied is reported as merely reachable rather than left saying "asking...".
  kernel.spawn("network", function()
    while not wd.closed do
      os.sleep(1000)
      if not wd.closed and pingAt > 0 and os.clock() - pingAt > TIMEOUT then
        pingAt = 0
        local serving = 0
        for _, r in ipairs(rows) do if r.state == "serving" then serving = serving + 1 end end
        status.text = #rows .. " reachable, " .. serving .. " serving"
        relabel()
      end
    end
  end, wd)
  return wd
end

return app
