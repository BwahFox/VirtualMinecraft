-- Music: a tracker on the sound chip (ROADMAP §7h §5/§7, rebuilt for U7 in session 21).
--
-- **What changed, and why.** Until now this was one sixteen-step pattern that looped forever: enough to prove
-- the chip works, not enough to write a song with. [name]'s words were "make it so music.lua can make actual
-- songs -- right now it's extremely basic". A song needs three things this did not have:
--
--   1. **Patterns and an order.** Sixteen steps is a bar or two. A song is a handful of patterns played in a
--      sequence, with the same pattern used more than once -- which is also why a tracker is *less* typing than
--      a piano roll, not more.
--   2. **A note keyboard.** Nudging a cell up and down a semitone at a time is not writing music. The bottom
--      row is a chromatic octave (z s x d c v g b h n j m) and the top row is the octave above (q w e r t y u),
--      which is the layout every tracker since Ultimate Soundtracker has used.
--   3. **Volume per note.** Dynamics are most of what separates a song from a test tone. Digits 1-9 set it,
--      0 puts it back to full.
--
-- **The file stays backwards compatible.** `snd.normalisesong` (sys.lua) turns an old single-`notes` song into
-- a one-pattern song, and every save still carries a `notes` mirror of the first pattern, so songs written
-- before tonight still play and /rom/examples/song.lua still reads what it expects.
local app = { id = "music", name = "Music", icon = "~" }
local NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" }
local WAVES = { "sqr", "tri", "saw", "sin", "nse" }
local DIR = "/disk/songs"
local LENGTHS = { 16, 32, 64 }

-- The tracker keyboard. The bottom row is one chromatic octave from the base; the top row is the white keys of
-- the octave above. The top row's black keys are deliberately absent: those are the digits, and digits set the
-- volume, which is used far more often than a C#5.
local PIANO = {
  z = 0, s = 1, x = 2, d = 3, c = 4, v = 5, g = 6, b = 7, h = 8, n = 9, j = 10, m = 11,
  q = 12, w = 14, e = 16, r = 17, t = 19, y = 21, u = 23,
}

local function noteName(m)
  if not m or m == 0 then return "---" end
  local n = NAMES[m % 12 + 1]
  local s = n .. (math.floor(m / 12) - 1)
  return #s < 3 and (s .. " ") or s
end

local function defaultSong()
  -- Two patterns and an order that uses one of them twice: the smallest thing that is a *song* rather than a
  -- loop, and the fastest way to show what the order bar is for.
  local song = { bpm = 120, steps = 16, wave = { 0, 1, 2, 3 }, patterns = {}, order = { 1, 1, 2, 1 } }
  local leadA = { 60, 0, 64, 0, 67, 0, 72, 0, 67, 0, 64, 0, 60, 0, 55, 0 }
  local leadB = { 65, 0, 69, 0, 72, 0, 77, 0, 72, 0, 69, 0, 65, 0, 60, 0 }
  local bassA = { 36, 0, 0, 0, 43, 0, 0, 0, 41, 0, 0, 0, 43, 0, 0, 0 }
  local bassB = { 41, 0, 0, 0, 48, 0, 0, 0, 46, 0, 0, 0, 48, 0, 0, 0 }
  for p, pair in ipairs({ { leadA, bassA }, { leadB, bassB } }) do
    local notes, vol = {}, {}
    for s = 1, 16 do
      notes[s] = { pair[1][s], pair[2][s], 0, 0 }
      vol[s] = { 9, 7, 9, 9 }
    end
    song.patterns[p] = { notes = notes, vol = vol }
  end
  return song
end

function app.open(args)
  local T = win.theme
  local KEY = win.KEY
  local r = args.restore or {}
  local song = snd.normalisesong(r.song or defaultSong())
  local octave = r.octave or 4
  local cur = { order = math.min(r.order or 1, #song.order), step = r.step or 1, ch = r.ch or 1 }
  local top = 1                     -- first visible step
  local playing = nil               -- { step, order } while the song runs
  local follow = true               -- the grid chases the playhead

  local wd = win.Window.new{ title = "Music", x = kernel.iconW + 6, y = 8,
    w = math.min(kernel.w - kernel.iconW - 10, 300), h = math.min(kernel.h - kernel.taskbarH - 14, 226) }
  wd.minW, wd.minH = T.fw * 32, T.fh * 14

  local cellH = T.fh + 1
  local numW = T.fw * 2 + 3
  local orderH = T.fh + 3
  -- Sized to the window rather than fixed, because the window this has to fit in is the one a 1x1 monitor
  -- gives you. Things give way in order of how little they cost: the buttons' spare width first (a button only
  -- needs to fit "Play"), then the volume digit, then the second column of buttons. The note columns never do --
  -- a tracker with no notes visible is not a tracker.
  local cellW, gridW, bw, bcols, showVol
  local function metrics(cw)
    local minBw = T.fw * 4 + 4          -- "Play" and "Save" are four characters; that is the floor
    local function fits() return gridW + 4 + bcols * (bw + 2) <= cw end
    local function squeeze() if not fits() then bw = math.max(minBw, math.floor((cw - gridW - 6) / bcols) - 2) end end
    showVol, bcols = true, 2
    cellW = T.fw * 4 + 3
    gridW = numW + cellW * 4 + 1
    bw = T.fw * 5 + 4
    squeeze()
    -- A single column of eight buttons costs 128 vertical pixels, which this window has, and it is what buys
    -- the volume digit on a 1x1 monitor's 180-pixel client. Losing the digit is the last resort, not the first.
    if not fits() then bcols = 1 bw = T.fw * 5 + 4 squeeze() end
    if not fits() then
      showVol = false
      cellW = T.fw * 3 + 3
      gridW = numW + cellW * 4 + 1
      bcols, bw = 2, T.fw * 5 + 4
      squeeze()
      if not fits() then bcols = 1 squeeze() end
    end
  end

  local order = wd:add(win.Label{})
  local grid = wd:add(win.Label{})
  local status = wd:add(win.Label{ text = "" })
  local names = { "Play", "Stop", "Wave", "Rest", "T-", "T+", "Save", "Load" }
  local buttons = {}
  for i, n in ipairs(names) do buttons[i] = wd:add(win.Button{ text = n, h = T.fh + 6 }) end
  order.layout = function(self, cw, ch)
    metrics(cw)                       -- first widget laid out, so everything below sees this window's numbers
    self.x, self.y, self.w, self.h = 0, 0, math.min(cw, gridW), orderH
  end
  grid.layout = function(self, cw, ch)
    self.x, self.y, self.w = 0, orderH + 2, gridW
    self.h = math.max(cellH * 4, ch - orderH - 2 - T.fh - 2)
  end
  status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, ch - T.fh, cw, T.fh end
  for i, b in ipairs(buttons) do
    b.layout = function(self, cw, ch)
      local col, row = (i - 1) % bcols, math.floor((i - 1) / bcols)
      self.x, self.y, self.w = gridW + 4 + col * (bw + 2), orderH + 2 + row * (T.fh + 8), bw
    end
  end
  wd:relayout()

  local function rowsVisible() return math.max(1, math.floor(grid.h / cellH)) end
  local function pat(i) return song.patterns[song.order[i or cur.order]] or song.patterns[1] end
  local function noteAt(s, c) local n = pat().notes[s] return n and n[c] or 0 end
  local function volAt(s, c) local v = pat().vol[s] return (v and v[c]) or 9 end

  local function setNote(s, c, n)
    local p = pat()
    p.notes[s] = p.notes[s] or { 0, 0, 0, 0 }
    p.notes[s][c] = n
  end
  local function setVol(s, c, v)
    local p = pat()
    p.vol[s] = p.vol[s] or { 9, 9, 9, 9 }
    p.vol[s][c] = v
  end

  local function scrollIntoView()
    local rows = rowsVisible()
    if cur.step < top then top = cur.step end
    if cur.step > top + rows - 1 then top = cur.step - rows + 1 end
    top = math.max(1, math.min(top, math.max(1, song.steps - rows + 1)))
  end

  local function setStatus()
    status.text = string.format("%dbpm p%d %d/%d s%02d c%d %s o%d",
      song.bpm, song.order[cur.order] or 1, cur.order, #song.order, cur.step, cur.ch,
      WAVES[(song.wave[cur.ch] or 0) + 1], octave)
    wd:invalidate()
  end

  ---------------------------------------------------------------------------------------------- drawing
  order.draw = function(self, ox, oy)
    local x0, y0 = ox + self.x, oy + self.y
    gfx.fill(x0, y0, self.w, self.h, T.field)
    local slotW = T.fw * 3
    local shown = math.max(1, math.floor((self.w - 2) / slotW))
    -- the order scrolls with the cursor, so a long song still shows where you are
    local from = math.max(1, math.min(cur.order - math.floor(shown / 2), math.max(1, #song.order - shown + 1)))
    for i = 0, shown - 1 do
      local oi = from + i
      local p = song.order[oi]
      if not p then break end
      local x = x0 + 1 + i * slotW
      local here = oi == cur.order
      local live = playing and playing.order == oi
      if here then gfx.fill(x, y0 + 1, slotW, self.h - 2, T.sel)
      elseif live then gfx.fill(x, y0 + 1, slotW, self.h - 2, T.ok) end
      gfx.text(x + 2, y0 + 2, string.format("%02d", p), here and T.selText or T.text, nil, T.font)
    end
    gfx.rect(x0, y0, self.w, self.h, T.frameDark)
  end

  grid.draw = function(self, ox, oy)
    local x0, y0 = ox + self.x, oy + self.y
    local rows = rowsVisible()
    gfx.fill(x0, y0, self.w, self.h, T.field)
    for i = 0, rows - 1 do
      local s = top + i
      if s > song.steps then break end
      local y = y0 + i * cellH
      if playing and playing.step == s and playing.order == cur.order then gfx.fill(x0, y, self.w, cellH, T.ok) end
      gfx.text(x0 + 1, y + 1, string.format("%02d", s), T.disabled, nil, T.font)
      for c = 1, 4 do
        local x = x0 + numW + (c - 1) * cellW
        local isSel = s == cur.step and c == cur.ch
        if isSel then gfx.fill(x, y, cellW, cellH, T.sel) end
        local n = noteAt(s, c)
        local label = noteName(n)
        if showVol then label = label .. (n > 0 and tostring(volAt(s, c)) or " ") end
        gfx.text(x + 2, y + 1, label, isSel and T.selText or T.text, nil, T.font)
      end
    end
    gfx.rect(x0, y0, self.w, self.h, T.frameDark)
  end

  ---------------------------------------------------------------------------------------------- playback
  local program
  local function stop()
    if program then program.stop() program = nil end
    playing = nil
    snd.stop()
    wd:invalidate()
  end
  local function play()
    stop()
    program = snd.playsong(song, { name = "music", alive = function() return not wd.closed end,
      onstep = function(s, oi)
        playing = { step = s, order = oi }
        if follow then
          -- following means the cursor's *pattern* follows too, otherwise the grid shows a pattern that is not
          -- the one you can hear
          cur.order = oi
          cur.step = s
          scrollIntoView()
        end
        wd:invalidate()
      end })
  end

  local function preview(n, c)
    if n and n > 0 then
      snd.channel(c, song.wave[c] or 0, snd.note(n), 0.5 * (volAt(cur.step, c) / 9), 0.005, 0.15, 0, 0.05)
    end
  end

  ---------------------------------------------------------------------------------------------- editing
  local function nudge(delta)
    local n = noteAt(cur.step, cur.ch)
    if n == 0 then n = (octave + 1) * 12 else n = math.max(12, math.min(108, n + delta)) end
    setNote(cur.step, cur.ch, n)
    preview(n, cur.ch)
    setStatus()
  end

  local function typeNote(semi)
    local n = (octave + 1) * 12 + semi
    if n > 108 then return end
    setNote(cur.step, cur.ch, n)
    preview(n, cur.ch)
    -- a tracker advances after a note: that is what makes typing a bar feel like typing
    cur.step = cur.step % song.steps + 1
    scrollIntoView()
    setStatus()
  end

  local function rest()
    setNote(cur.step, cur.ch, 0)
    cur.step = cur.step % song.steps + 1
    scrollIntoView()
    setStatus()
  end

  --- Growing or shrinking every pattern at once: `steps` is a property of the song, because a pattern of a
  --- different length to its neighbours is a feature no one asked for and a lot of arithmetic.
  local function setLength(n)
    song.steps = n
    for _, p in ipairs(song.patterns) do
      for s = 1, n do
        p.notes[s] = p.notes[s] or { 0, 0, 0, 0 }
        p.vol[s] = p.vol[s] or { 9, 9, 9, 9 }
      end
      for s = n + 1, 64 do p.notes[s] = nil p.vol[s] = nil end
    end
    cur.step = math.min(cur.step, n)
    scrollIntoView()
    setStatus()
  end

  local function blankPattern()
    local notes, vol = {}, {}
    for s = 1, song.steps do notes[s] = { 0, 0, 0, 0 } vol[s] = { 9, 9, 9, 9 } end
    return { notes = notes, vol = vol }
  end

  local function copyPattern(p)
    local notes, vol = {}, {}
    for s = 1, song.steps do
      local a, b = p.notes[s] or { 0, 0, 0, 0 }, p.vol[s] or { 9, 9, 9, 9 }
      notes[s] = { a[1] or 0, a[2] or 0, a[3] or 0, a[4] or 0 }
      vol[s] = { b[1] or 9, b[2] or 9, b[3] or 9, b[4] or 9 }
    end
    return { notes = notes, vol = vol }
  end

  --- Deleting a pattern is not the same as deleting a slot in the order (F4), which is the thing that confused
  --- [name] into asking. This removes the *pattern* from the song, renumbers every order entry above it, and
  --- takes the slots that played it with it — a slot pointing at a pattern that no longer exists would be a
  --- silent bar of nothing. A song always keeps at least one pattern and one slot.
  --- **The index is an argument, not something re-read here.** It used to read song.order[cur.order] at the
  --- moment the confirmation was accepted, and the cursor moves under you while the song plays and `follow` is
  --- on — so by the time you pressed Delete it was pointing at the next slot and deleted the wrong pattern
  --- ([name], 2026-08-29). The caller decides what it meant when the menu opened.
  local function deletePattern(idx)
    if #song.patterns <= 1 then
      kernel.notify("A song needs at least one pattern", 3)
      return
    end
    idx = math.max(1, math.min(idx or (song.order[cur.order] or 1), #song.patterns))
    table.remove(song.patterns, idx)
    -- Rebuilt in place rather than replaced: a song that is playing holds this very table.
    local kept = {}
    for _, o in ipairs(song.order) do
      if o < idx then
        kept[#kept + 1] = o
      elseif o > idx then
        kept[#kept + 1] = o - 1              -- everything above shifted down by one
      end
    end
    if #kept == 0 then kept[1] = 1 end
    for i = #song.order, 1, -1 do song.order[i] = nil end
    for i, o in ipairs(kept) do song.order[i] = o end
    cur.order = math.max(1, math.min(cur.order, #song.order))
    scrollIntoView()
    setStatus()
  end

  ---------------------------------------------------------------------------------------------- clicks
  grid.press = function(self, lx, ly)
    local s = top + math.floor((ly - self.y) / cellH)
    local c = math.floor((lx - self.x - numW) / cellW) + 1
    if s >= 1 and s <= song.steps and c >= 1 and c <= 4 then cur.step, cur.ch = s, c setStatus() end
  end
  order.press = function(self, lx, ly)
    local slotW = T.fw * 3
    local shown = math.max(1, math.floor((self.w - 2) / slotW))
    local from = math.max(1, math.min(cur.order - math.floor(shown / 2), math.max(1, #song.order - shown + 1)))
    local i = from + math.floor((lx - self.x - 1) / slotW)
    if i >= 1 and i <= #song.order then cur.order = i scrollIntoView() setStatus() end
  end

  ---------------------------------------------------------------------------------------------- keys
  grid.key = function(self, code, down, mods)
    if not down then return false end
    if code == KEY.up then cur.step = math.max(1, cur.step - 1)
    elseif code == KEY.down then cur.step = math.min(song.steps, cur.step + 1)
    elseif code == KEY.left then cur.ch = math.max(1, cur.ch - 1)
    elseif code == KEY.right then cur.ch = math.min(4, cur.ch + 1)
    elseif code == KEY.home then cur.step = 1
    elseif code == KEY["end"] then cur.step = song.steps
    elseif code == KEY.pgup then octave = math.max(0, octave - 1)
    elseif code == KEY.pgdn then octave = math.min(7, octave + 1)
    elseif code == KEY.tab then
      cur.order = (cur.order - 1 + (mods.shift and -1 or 1)) % #song.order + 1
    elseif code == KEY.delete or code == KEY.backspace then rest() return true
    elseif code == KEY.f5 then if program then stop() else play() end
    elseif code == KEY.f1 then
      song.order[cur.order] = math.max(1, (song.order[cur.order] or 1) - 1)
    elseif code == KEY.f2 then
      song.order[cur.order] = math.min(#song.patterns, (song.order[cur.order] or 1) + 1)
    elseif code == KEY.f3 then
      table.insert(song.order, cur.order + 1, song.order[cur.order] or 1)
      cur.order = cur.order + 1
    elseif code == KEY.f4 then
      if #song.order > 1 then
        table.remove(song.order, cur.order)
        cur.order = math.min(cur.order, #song.order)
      end
    else return false end
    scrollIntoView()
    setStatus()
    return true
  end

  --- Note entry. Characters rather than scancodes because a piano layout *is* the letters: win.KEY only carries
  --- the dozen the ROM's apps needed, and a tracker needs nineteen.
  grid.char = function(self, cp)
    local ch = string.char(cp):lower()
    local semi = PIANO[ch]
    if semi then typeNote(semi) return true end
    if ch >= "1" and ch <= "9" then
      setVol(cur.step, cur.ch, tonumber(ch))
      setStatus()
      return true
    end
    if ch == "0" then setVol(cur.step, cur.ch, 9) setStatus() return true end
    if ch == "-" then octave = math.max(0, octave - 1) setStatus() return true end
    if ch == "+" or ch == "=" then octave = math.min(7, octave + 1) setStatus() return true end
    return false
  end

  ---------------------------------------------------------------------------------------------- files
  local function songToTable()
    -- `notes` is the compatibility mirror sys.lua documents: the first pattern in the order, so a program
    -- written against the old format still gets something that plays.
    local out = { bpm = song.bpm, steps = song.steps, wave = song.wave, patterns = song.patterns,
      order = song.order }
    out.notes = (song.patterns[song.order[1]] or song.patterns[1]).notes
    return out
  end

  local function doSave()
    win.prompt("Save song", "Name", r.name or "song", function(name)
      if not name or name == "" then return end
      if not fs.validname(name) then win.info("Music", { "Not a usable name.", fs.NAME_HELP }) return end
      local ok, err = pcall(function()
        if not fs.exists(DIR) then fs.mkdir(DIR) end
        fs.write(DIR .. "/" .. name .. ".json", json.encode(songToTable()))
      end)
      kernel.notify(ok and ("Saved " .. DIR .. "/" .. name .. ".json") or tostring(err), 3)
      r.name = name
    end)
  end

  local function doLoad()
    win.prompt("Load song", "Name", r.name or "song", function(name)
      if not name or name == "" then return end
      local ok, err = pcall(function()
        song = snd.loadsong(DIR .. "/" .. name .. ".json")
        cur.order, cur.step, cur.ch = 1, 1, 1
        top = 1
      end)
      kernel.notify(ok and "Loaded" or tostring(err), 3)
      r.name = name
      setStatus()
    end)
  end

  ---------------------------------------------------------------------------------------------- menu
  local KEYS_HELP = {
    "Notes: z s x d c v g b h n j m  (one octave)",
    "       q w e r t y u  (the octave above)",
    "Volume: 1-9, 0 for full.  Octave: PgUp/PgDn",
    "Rest: Delete.  Play/Stop: F5",
    "Pattern here: F1/F2.  Add/remove SLOT: F3/F4",
    "Delete a PATTERN: right-click menu",
    "Tab walks the order; click the bar to jump.",
  }

  wd.onrightpress = function(_, lx, ly, mx, my)
    -- Right-clicking a slot in the order bar selects it first, so the menu acts on the pattern under the
    -- pointer rather than on wherever the cursor happened to be (the same thing Sheet does).
    if order:contains(lx, ly) then order:press(lx, ly) end
    local target = song.order[cur.order] or 1
    local lengths = {}
    for _, n in ipairs(LENGTHS) do
      lengths[#lengths + 1] = { text = n .. " steps", onclick = function() setLength(n) end }
    end
    win.menu(mx, my, {
      { text = "Add pattern", onclick = function()
        song.patterns[#song.patterns + 1] = blankPattern()
        song.order[#song.order + 1] = #song.patterns
        cur.order = #song.order
        setStatus()
      end },
      { text = "Duplicate this pattern", onclick = function()
        song.patterns[#song.patterns + 1] = copyPattern(pat())
        song.order[cur.order] = #song.patterns
        setStatus()
      end },
      { text = "Clear this pattern", onclick = function()
        local p = pat()
        for s = 1, song.steps do p.notes[s] = { 0, 0, 0, 0 } p.vol[s] = { 9, 9, 9, 9 } end
        setStatus()
      end },
      { text = "Delete pattern " .. target, disabled = #song.patterns <= 1,
        onclick = function()
          -- `target` was decided when this menu opened; nothing it reads can drift while the dialog is up
          win.ask("Delete pattern", "Pattern " .. target .. " and every slot playing it?",
            { "Delete", "Cancel" }, function(b) if b == "Delete" then deletePattern(target) end end)
        end },
      { sep = true },
      { text = "Pattern length", submenu = lengths },
      { text = follow and "Stop following the playhead" or "Follow the playhead",
        onclick = function() follow = not follow end },
      { sep = true },
      { text = "Keys...", onclick = function() win.info("Music keys", KEYS_HELP) end },
    })
    return true
  end

  buttons[1].onclick = play
  buttons[2].onclick = stop
  buttons[3].onclick = function() song.wave[cur.ch] = ((song.wave[cur.ch] or 0) + 1) % 5 setStatus() end
  buttons[4].onclick = rest
  buttons[5].onclick = function() song.bpm = math.max(40, song.bpm - 10) setStatus() end
  buttons[6].onclick = function() song.bpm = math.min(300, song.bpm + 10) setStatus() end
  buttons[7].onclick = doSave
  buttons[8].onclick = doLoad

  wd.onclose = stop
  -- the harness (TESTING.md): the emulator's `exec` drives these
  wd.play, wd.stop, wd.deletePattern = play, stop, deletePattern
  wd.addPattern = function()
    song.patterns[#song.patterns + 1] = blankPattern()
    song.order[#song.order + 1] = #song.patterns
    setStatus()
  end
  wd.harness = {
    note = function(semi) typeNote(semi) end,
    at = function(o, s, c) cur.order, cur.step, cur.ch = o, s, c scrollIntoView() setStatus() end,
    length = setLength, save = doSave, load = doLoad,
    state = function()
      return { bpm = song.bpm, steps = song.steps, patterns = #song.patterns, order = #song.order,
        here = song.order[cur.order], step = cur.step, ch = cur.ch, octave = octave,
        note = noteAt(cur.step, cur.ch), vol = volAt(cur.step, cur.ch) }
    end,
    song = function() return songToTable() end,
  }
  wd.save = function()
    return { song = songToTable(), step = cur.step, ch = cur.ch, order = cur.order, octave = octave, name = r.name }
  end
  wd:setfocus(grid)
  setStatus()
  return wd
end

function app.save(wd) return wd.save and wd.save() or nil end

return app
