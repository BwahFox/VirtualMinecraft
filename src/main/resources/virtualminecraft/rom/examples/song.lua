-- Play a song you wrote in Music, and draw it while it plays. Q leaves.
-- `examples` lists these; `examples song [name]` runs this one; copy it to your own disk to change it:
--   cp /rom/examples/song.lua /disk/mysong.lua        then     start /disk/mysong.lua
local name = ...                                   -- `examples song tune` uses /disk/songs/tune.json
local DIR = "/disk/songs"
local KEY = win.KEY

local function firstSong()
  if not fs.exists(DIR) then return nil end
  for _, e in ipairs(fs.list(DIR)) do
    if not e.dir and e.name:match("%.json$") then return (e.name:gsub("%.json$", "")) end
  end
end

name = name or firstSong()
if not name then return "No songs yet: write one in Music, press Save, then run this again." end
local song = snd.loadsong(DIR .. "/" .. name .. ".json")   -- bpm, steps, wave[4], notes[step][channel]

local w, h = gfx.size()
local me = kernel.top() and kernel.top().program
local quit = false
if me then me.key = function(code, down) if down and (code == KEY.q or code == KEY.esc) then quit = true end end end

local bw = math.max(2, math.floor(w / song.steps))
local function draw(step)
  gfx.clear(1)
  gfx.text(4, 4, name .. "  " .. song.bpm .. " bpm   Q leaves", 15, 1, 1)
  for s = 1, song.steps do
    for c = 1, 4 do
      local n = song.notes[s] and song.notes[s][c] or 0
      if n and n > 0 then
        local y = h - 8 - math.floor((n - 24) / 72 * (h - 40))
        gfx.fill((s - 1) * bw + 1, y, bw - 2, 4, s == step and 15 or (9 + c))
      end
    end
  end
end

-- The same call the Music app's Play button makes: it plays in the background and returns at once.
local step = 0
local playing = snd.playsong(song, { onstep = function(s) step = s end })
local shown = -1
while not quit do
  if step ~= shown then shown = step draw(step) gfx.present() end
  os.sleep(20)
end
playing.stop()
return "song " .. name
