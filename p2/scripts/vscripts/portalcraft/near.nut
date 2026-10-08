// test helper: boxes near the player
local o = GetPlayer().GetOrigin();
foreach (k, e in ::PC.boxes) {
	local c = e.GetOrigin();
	if (fabs(c.x - o.x) < 80 && fabs(c.y - o.y) < 80) printl("[near] " + k + " " + c);
}
printl("[near] player " + o + " deferred " + ::PC.deferred.len());
