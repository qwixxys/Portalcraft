// Portalcraft: invisible solid boxes in Portal 2 where Minecraft has blocks (so you can stand on them).
// A box covers whole 32-unit Source grid cells: min corner cell (x, y, z) and size in cells (sx, sy, sz); neighbouring
// blocks share one box (Portal 2 has room for only so many entities). Driven by the ReShade add-on through "script".
if (!("PC" in getroottable())) {
	::PC <- { boxes = {} };
}

if (!("deferred" in ::PC)) ::PC.deferred <- {};
if (!("bounds" in ::PC)) ::PC.bounds <- {};

// A block that appears inside the player: if her feet are in the box's upper half (placing a block under
// yourself mid-jump) she is put on top of it, otherwise the box waits until she has moved out.
::PC_ClearOfPlayer <- function(lo, hi) {
	local p = GetPlayer();
	local o = p.GetOrigin();
	local plo = o + p.GetBoundingMins(), phi = o + p.GetBoundingMaxs();
	if (plo.x >= hi.x || phi.x <= lo.x || plo.y >= hi.y || phi.y <= lo.y || plo.z >= hi.z || phi.z <= lo.z)
		return true;
	if (o.z >= (lo.z + hi.z) * 0.5) {
		p.SetOrigin(Vector(o.x, o.y, hi.z + 0.5));
		return true;
	}
	return false;
}

::PC_Retry <- function() {
	local again = {};
	foreach (k, v in ::PC.deferred) {
		if (k in ::PC.boxes) continue;
		if (!PC_Box(v[0], v[1], v[2], v[3], v[4], v[5])) again[k] <- v;
	}
	::PC.deferred <- again;
	if (again.len() > 0) EntFireByHandle(GetPlayer(), "RunScriptCode", "PC_Retry()", 0.2, null, null);
}

::PC_Box <- function(x, y, z, sx, sy, sz) {
	local k = x + " " + y + " " + z + " " + sx + " " + sy + " " + sz;
	if (k in ::PC.boxes && ::PC.boxes[k].IsValid()) return true;
	local lo = Vector(x * 32, y * 32, z * 32), hi = Vector((x + sx) * 32, (y + sy) * 32, (z + sz) * 32);
	if (!PC_ClearOfPlayer(lo, hi)) {
		local first = ::PC.deferred.len() == 0;
		::PC.deferred[k] <- [x, y, z, sx, sy, sz];
		if (first) EntFireByHandle(GetPlayer(), "RunScriptCode", "PC_Retry()", 0.2, null, null);
		return false;
	}
	local hx = sx * 16, hy = sy * 16, hz = sz * 16;
	local e = CreateProp("prop_dynamic", Vector(lo.x + hx, lo.y + hy, lo.z + hz), "models/player/chell/player.mdl", 0);
	e.__KeyValueFromString("targetname", "pc_box");
	e.__KeyValueFromInt("rendermode", 10);
	e.__KeyValueFromInt("solid", 2);
	e.SetSize(Vector(-hx, -hy, -hz), Vector(hx, hy, hz));
	EntFireByHandle(e, "DisableDraw", "", 0, null, null);
	EntFireByHandle(e, "DisableShadow", "", 0, null, null);
	EntFireByHandle(e, "EnableCollision", "", 0, null, null);
	::PC.boxes[k] <- e;
	::PC.bounds[k] <- [lo, hi];
	return true;
}

::PC_Unbox <- function(k) {
	if (k in ::PC.deferred) delete ::PC.deferred[k];
	if (k in ::PC.bounds) delete ::PC.bounds[k];
	if (!(k in ::PC.boxes)) return;
	local e = ::PC.boxes[k];
	delete ::PC.boxes[k];
	if (e.IsValid()) e.Destroy();
}

// "x,y,z,sx,sy,sz;..." lists
::PC_Apply <- function(add, rem) {
	foreach (part in split(rem, ";")) {
		local v = split(part, ",");
		if (v.len() == 6) PC_Unbox(v[0] + " " + v[1] + " " + v[2] + " " + v[3] + " " + v[4] + " " + v[5]);
	}
	foreach (part in split(add, ";")) {
		local v = split(part, ",");
		if (v.len() == 6)
			PC_Box(v[0].tointeger(), v[1].tointeger(), v[2].tointeger(), v[3].tointeger(), v[4].tointeger(), v[5].tointeger());
	}
	PC_Buttons();
}

// Every load: whatever boxes the save game had go (they may come from an older version of this script)
::PC_Clear <- function() {
	foreach (k, e in ::PC.boxes) if (e.IsValid()) e.Destroy();
	::PC.boxes <- {};
	::PC.bounds <- {};
	::PC.deferred <- {};
}
printl("[portalcraft] solids ready");

// Minecraft blocks press Portal 2's floor buttons: a box on a button's pad presses it, and when the last one
// is gone the button is released again (only buttons we pressed ourselves).
if (!("pressed" in ::PC)) ::PC.pressed <- {};

::PC_Buttons <- function() {
	foreach (cls in ["prop_floor_button", "prop_floor_cube_button", "prop_floor_ball_button", "prop_under_floor_button"]) {
		local b = null;
		while ((b = Entities.FindByClassname(b, cls)) != null) {
			local o = b.GetOrigin();
			local on = false;
			foreach (k, lh in ::PC.bounds) {
				local lo = lh[0], hi = lh[1];
				// a block within 24 units of the pad's centre sideways, its bottom between 24 below and 40 above the pad
				if (lo.x < o.x + 24 && hi.x > o.x - 24 && lo.y < o.y + 24 && hi.y > o.y - 24 && lo.z < o.z + 40 && hi.z > o.z + 8) {
					on = true;
					break;
				}
			}
			local id = b.tostring();
			local was = id in ::PC.pressed;
			if (on && !was) {
				::PC.pressed[id] <- true;
				EntFireByHandle(b, "PressIn", "", 0, null, null);
				printl("[portalcraft] block presses " + b);
			} else if (!on && was) {
				delete ::PC.pressed[id];
				EntFireByHandle(b, "PressOut", "", 0, null, null);
				printl("[portalcraft] block released " + b);
			}
		}
	}
}
