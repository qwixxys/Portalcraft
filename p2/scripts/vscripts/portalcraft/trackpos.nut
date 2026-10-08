// test helper: log the player's position every 0.1 s for 3 s
::PC_TrackN <- 0;
::PC_Track <- function() {
	local p = GetPlayer();
	printl("[track] " + Time() + " " + p.GetOrigin() + " vel " + p.GetVelocity());
	if (++::PC_TrackN < 30) EntFireByHandle(p, "RunScriptCode", "PC_Track()", 0.1, null, null);
}
PC_Track();
