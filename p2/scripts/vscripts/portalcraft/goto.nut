// test helper: stand next to the map's first floor button (or cube)
local e = Entities.FindByClassname(null, "prop_floor_button");
if (!e) e = Entities.FindByClassname(null, "prop_weighted_cube");
if (e) {
	GetPlayer().SetOrigin(e.GetOrigin() + Vector(-160, 0, 24));
	printl("[goto] " + e + " at " + e.GetOrigin());
}
