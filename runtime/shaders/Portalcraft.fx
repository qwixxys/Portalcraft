// Portalcraft: composites Minecraft's frame into Portal 2 by depth.
// PC_MC     = (Minecraft near, far (blocks), depth mode, has frame)
// PC_P2     = (Portal 2 near, far (units), cursor visible, debug mode: write 1-6 into portalcraft_runtime/debug.txt;
//             1 Portal 2 depth, 2 no depth test, 3 Minecraft depth, 4 cell grid + holes, 5 hole texture, 6 cell colours)
// PC_Cursor = (x, y) in 0..1 for Minecraft screens, z = 1: colour layers hold R,G,B,A bytes in a B,G,R,A texture
// PC_Eye/Fwd/Right/Up = Portal 2's camera (Source units): eye + where it is (0 open, 1 inside a wall's hole, 2 deep in
//                       the walls); basis + tan(half fov) y, x;
//                       up.w = hole grid present
// PC_Grid   = origin of the hole grid (Source cells); PC_HOLES = 128 x 128 x 64 cells, 4 z cells per texel (bytes R,G,B,A)

uniform float4 PC_MC = float4(0.05, 1000.0, 0.0, 0.0);
uniform float4 PC_P2 = float4(7.0, 28377.9, 0.0, 0.0);
uniform float4 PC_Cursor = float4(0.5, 0.5, 0.0, 0.0);
uniform float4 PC_Eye = float4(0.0, 0.0, 0.0, 0.0);
uniform float4 PC_Fwd = float4(1.0, 0.0, 0.0, 0.75);
uniform float4 PC_Right = float4(0.0, -1.0, 0.0, 1.333);
uniform float4 PC_Up = float4(0.0, 0.0, 1.0, 0.0);
uniform float4 PC_Grid = float4(0.0, 0.0, 0.0, 0.0);
// the camera the shown Minecraft frame was drawn from (same layout as PC_Fwd/Right/Up; SUp.w = 1: turn it to this one)
uniform float4 PC_SFwd = float4(1.0, 0.0, 0.0, 0.75);
uniform float4 PC_SRight = float4(0.0, -1.0, 0.0, 1.333);
uniform float4 PC_SUp = float4(0.0, 0.0, 1.0, 0.0);
uniform float4 PC_SEye = float4(0.0, 0.0, 0.0, 0.0); // and where it was

texture BackBufferTex : COLOR;
texture DepthBufferTex : DEPTH;
texture PC_ColorTex : PC_COLOR;
texture PC_DepthTex : PC_MCDEPTH;
texture PC_OverlayTex : PC_OVERLAY;
texture PC_HolesTex : PC_HOLES;

sampler sBack { Texture = BackBufferTex; };
sampler sP2Depth { Texture = DepthBufferTex; MagFilter = POINT; MinFilter = POINT; MipFilter = POINT; };
sampler sColor { Texture = PC_ColorTex; MagFilter = POINT; MinFilter = POINT; MipFilter = POINT; };
sampler sDepth { Texture = PC_DepthTex; MagFilter = POINT; MinFilter = POINT; MipFilter = POINT; };
sampler sOverlay { Texture = PC_OverlayTex; MagFilter = POINT; MinFilter = POINT; MipFilter = POINT; };
sampler sHoles { Texture = PC_HolesTex; MagFilter = POINT; MinFilter = POINT; MipFilter = POINT; AddressU = CLAMP; AddressV = CLAMP; };

void VS(in uint id : SV_VertexID, out float4 pos : SV_Position, out float2 uv : TEXCOORD)
{
	uv.x = (id == 2) ? 2.0 : 0.0;
	uv.y = (id == 1) ? 2.0 : 0.0;
	pos = float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
}

// Portal 2: standard D3D9 depth, 0 at the near plane
float p2_distance(float d)
{
	float n = PC_P2.x, f = PC_P2.y;
	return n * f / (f - d * (f - n));
}

// Minecraft 26.x: reversed depth (1 at near, 0 at far), result in Source units (32 per block)
float mc_distance(float w)
{
	float n = PC_MC.x, f = PC_MC.y, z;
	if (PC_MC.z < 0.5)
		z = 2.0 * n * f / ((2.0 * w - 1.0) * (f - n) + n + f);
	else
		z = f * n / ((f - n) * w + n);
	return z * 32.0;
}

// Where Portal 2's pixel is in the world (Source units), from its depth and camera
float3 p2_world(float2 uv, float z)
{
	float2 ndc = float2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
	return PC_Eye.xyz + PC_Fwd.xyz * z + PC_Right.xyz * (ndc.x * PC_Right.w * z) + PC_Up.xyz * (ndc.y * PC_Fwd.w * z);
}

// Is Portal 2's surface at this point a wall the player has broken? (a point just behind the surface, in its cell)
bool in_hole(float3 p)
{
	float3 c = floor(p / 32.0) - PC_Grid.xyz;
	if (any(c < 0.0) || c.x >= 128.0 || c.y >= 128.0 || c.z >= 64.0)
		return false;
	float group = floor(c.z / 4.0);
	float2 texel = float2(c.x + (group - 4.0 * floor(group / 4.0)) * 128.0, c.y + floor(group / 4.0) * 128.0);
	float4 t = tex2Dlod(sHoles, float4((texel + 0.5) / 512.0, 0.0, 0.0));
	float k = c.z - 4.0 * group; // byte k of the texel (the upload keeps memory order: R, G, B, A)
	float v = k < 0.5 ? t.r : (k < 1.5 ? t.g : (k < 2.5 ? t.b : t.a));
	return v > 0.5;
}

// Where this pixel's view ray is in the Minecraft frame, which may come from a camera a frame older (Minecraft
// didn't finish in time): the ray is turned into that camera, so Minecraft stays on Portal 2's walls when you
// look around. Returns the Minecraft texture coordinate (rows bottom-up) and how much longer the ray is per unit of
// that camera's depth (to compare distances), or x < 0 outside its view.
// Minecraft's colour layers, red and blue put right when they were uploaded byte for byte
float4 mc_tex(sampler s, float2 uv)
{
	float4 c = tex2D(s, uv);
	return PC_Cursor.z > 0.5 ? c.bgra : c;
}

float3 mc_ray(float2 uv)
{
	if (PC_SUp.w < 0.5)
		return float3(uv.x, 1.0 - uv.y, 1.0);
	float2 ndc = float2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
	float3 d = PC_Fwd.xyz + PC_Right.xyz * (ndc.x * PC_Right.w) + PC_Up.xyz * (ndc.y * PC_Fwd.w); // forward component 1
	float z = dot(d, PC_SFwd.xyz);
	if (z < 0.01)
		return float3(-1.0, -1.0, 1.0);
	float2 n = float2(dot(d, PC_SRight.xyz) / (z * PC_SRight.w), dot(d, PC_SUp.xyz) / (z * PC_SFwd.w));
	float2 s = float2(n.x * 0.5 + 0.5, 0.5 - n.y * 0.5);
	if (any(s < 0.0) || any(s > 1.0))
		return float3(-1.0, -1.0, 1.0);
	return float3(s.x, 1.0 - s.y, 1.0 / z);
}

float3 PS(float4 pos : SV_Position, float2 uv : TEXCOORD) : SV_Target
{
	float3 col = tex2D(sBack, uv).rgb;
	if (PC_P2.w > 0.5 && PC_P2.w < 1.5)
		return saturate(p2_distance(tex2D(sP2Depth, uv).r) / 1024.0).xxx;
	if (PC_MC.w < 0.5)
		return col;
	if (PC_P2.w > 2.5 && PC_P2.w < 3.5)
		return saturate(mc_distance(max(tex2D(sDepth, float2(uv.x, 1.0 - uv.y)).r, 1e-6)) / 1024.0).xxx;
	if (PC_P2.w > 4.5 && PC_P2.w < 5.5) // debug 5: the hole grid texture itself (any hole = bright), and whether the grid is on
	{
		float4 t = tex2Dlod(sHoles, float4(uv, 0.0, 0.0));
		return float3(max(max(t.r, t.g), max(t.b, t.a)), PC_Up.w * 0.25, 0.0);
	}
	float2 mcuv = float2(uv.x, 1.0 - uv.y); // Minecraft rows are bottom-up
	float d2 = tex2D(sP2Depth, uv).r;
	float p2z = p2_distance(d2);
	bool hole = false;
	float3 wp = p2_world(uv, p2z);
	// a point just behind Portal 2's surface, in the cell the surface belongs to: 1 unit along the surface's normal
	// (from how the depth changes across the screen). Along the view ray instead, a grazing look at a floor reached
	// into the next cell, and a hole's far edge showed whatever Minecraft has under the floor next to it.
	float3 dir = normalize(wp - PC_Eye.xyz);
	// how much Portal 2's depth changes from one pixel to the next: a floor seen edge-on changes a lot, and Minecraft's
	// smaller frame is sampled up to a pixel off, so the faces lying on such a surface need that much more margin
	float slope = fwidth(p2z);
	float3 nrm = cross(ddx(wp), ddy(wp));
	float nl = length(nrm);
	float3 back = dir * 2.0;
	if (nl > 1e-6)
	{
		nrm /= nl;
		back = dot(nrm, dir) > 0.0 ? nrm : -nrm;
	}
	if (PC_Up.w > 0.5 && d2 < 0.999999)
	{
		if (PC_P2.w > 5.5) // debug 6: the cell under each pixel relative to the grid origin, as colour
			return saturate((floor((wp + back) / 32.0) - PC_Grid.xyz) / float3(128.0, 128.0, 64.0));
		if (PC_P2.w > 3.5) // debug 4: the 32-unit grid on Portal 2's surfaces, holes red
		{
			float3 g = abs(frac(wp / 32.0) - 0.5);
			float edge = max(max(g.x, g.y), g.z) > 0.47 ? 1.0 : 0.0;
			col = lerp(col, float3(0, 1, 0), edge * 0.6);
			if (in_hole(wp + back)) col = lerp(col, float3(1, 0, 0), 0.6);
			return col;
		}
		hole = in_hole(wp + back);
	}
	float3 ray = mc_ray(uv);
	float2 wuv = ray.xy; // the Minecraft world layer, turned to this camera (the hand/HUD layer stays on screen)
	float w = ray.x < 0.0 ? 0.0 : tex2D(sDepth, wuv).r;
	if (hole)
	{
		// the wall is gone: whatever Minecraft has behind it. Only its sky (nothing there) keeps Portal 2's pixel: along
		// a hole's rim Minecraft's ray just misses the hole's side, which showed as a thin line of sky
		if (w > 0.0)
			col = mc_tex(sColor, wuv).rgb;
	}
	else if (w > 0.0)
	{
		float mcz = mc_distance(w) * ray.z; // along this camera's view axis, like p2z
		// Minecraft must be clearly in front: its rock behind Portal 2's walls lies exactly on their surfaces (more so
		// when its frame was drawn from elsewhere, which only happens when Minecraft got stuck)
		float moved = PC_SUp.w > 0.5 ? distance(PC_Eye.xyz, PC_SEye.xyz) : 0.0;
		// (the slope margin only in the open: from inside Portal 2's wall its depth has edges everywhere)
		if (mcz < p2z - (1.0 + 0.0015 * p2z + 2.0 * moved + (PC_Eye.w < 0.5 ? 2.0 * slope : 0.0)) || PC_P2.w > 1.5)
			col = mc_tex(sColor, wuv).rgb;
	}
	else if (PC_Eye.w > 1.5 && ray.x >= 0.0)
		col = mc_tex(sColor, wuv).rgb; // inside the walls: Portal 2 seen from behind is not real, Minecraft's sky is
	float4 o = mc_tex(sOverlay, mcuv);
	col = col * (1.0 - o.a) + o.rgb; // the hand/HUD layer is premultiplied
	if (PC_P2.z > 0.5)
	{
		float2 d = (uv - PC_Cursor.xy) * float2(BUFFER_WIDTH, BUFFER_HEIGHT) / max(1.0, BUFFER_HEIGHT / 720.0);
		if (d.x >= 0 && d.y >= 0 && d.x < 12 && d.y < 18 && d.x <= d.y * 0.7)
			col = (d.x < 1.5 || d.x > d.y * 0.7 - 1.5 || d.y > 16.5) ? float3(0, 0, 0) : float3(1, 1, 1);
	}
	return col;
}

technique Portalcraft
{
	pass
	{
		VertexShader = VS;
		PixelShader = PS;
	}
}
