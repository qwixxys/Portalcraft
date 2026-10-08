// test helper: stand 70 units from the first cube, looking at it
local c = Entities.FindByClassname(null, "prop_weighted_cube");
if (c) {
	local o = c.GetOrigin();
	GetPlayer().SetOrigin(o + Vector(-70, 0, -o.z + o.z - 16));
	printl("[gotocube] cube at " + o);
} else printl("[gotocube] no cube");
