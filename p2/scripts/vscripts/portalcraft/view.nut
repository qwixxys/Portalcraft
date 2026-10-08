// Portalcraft: in third person Minecraft draws Steve, so Portal 2 hides Chell and her portal gun.
::PC_Third <- function(on) {
	local input = on ? "DisableDraw" : "EnableDraw";
	EntFireByHandle(GetPlayer(), input, "", 0, null, null);
	local w = null;
	while ((w = Entities.FindByClassname(w, "weapon_portalgun")) != null) EntFireByHandle(w, input, "", 0, null, null);
}

// Portalcraft: while Minecraft moves the player (holes in the walls, elytra), Chell follows it.
::PC_Move <- function(x, y, z, vx, vy, vz) {
	local p = GetPlayer();
	if (p == null) return;
	p.SetOrigin(Vector(x, y, z));
	p.SetVelocity(Vector(vx, vy, vz));
}
