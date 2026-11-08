"""The icon sketch: an iron ore block with the glowing weak point on its face, an iron pickaxe striking the point,
an oak tree behind starting to fall. A layout reference for the image model, not the icon itself.
Built from vanilla 1.21.1 textures (share/icon/mc); the pickaxe is its item texture extruded pixel by pixel.

  blender -b -P tools/icon_scene.py
Blender axes: x right, y away from the camera, z up; 1 unit is a block. The ore block spans -0.5..0.5.
"""
import math
import os
import random

import bmesh
import bpy
from mathutils import Matrix, Vector

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "share", "icon"))
MARKER = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "weakpoints", "textures", "misc", "weak_point.png"))
random.seed(3)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene


def mc(name):
	return os.path.join(ROOT, "mc", name + ".png")


def material(name, path=None, color=None, emit=0.0, alpha=False, tint=None):
	m = bpy.data.materials.new(name)
	m.use_nodes = True
	nt = m.node_tree
	bsdf = nt.nodes["Principled BSDF"]
	bsdf.inputs["Roughness"].default_value = 0.9
	if path:
		tex = nt.nodes.new("ShaderNodeTexImage")
		tex.image = bpy.data.images.load(path, check_existing=True)
		tex.interpolation = "Closest"
		color_out = tex.outputs["Color"]
		if tint:
			mix = nt.nodes.new("ShaderNodeMix")
			mix.data_type = "RGBA"
			mix.blend_type = "MULTIPLY"
			mix.inputs["Factor"].default_value = 1.0
			mix.inputs["B"].default_value = (*tint, 1)
			nt.links.new(color_out, mix.inputs["A"])
			color_out = mix.outputs["Result"]
		nt.links.new(color_out, bsdf.inputs["Base Color"])
		if alpha:
			nt.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
		if emit:
			nt.links.new(color_out, bsdf.inputs["Emission Color"])
	else:
		bsdf.inputs["Base Color"].default_value = (*color, 1)
		if emit:
			bsdf.inputs["Emission Color"].default_value = (*color, 1)
	bsdf.inputs["Emission Strength"].default_value = emit
	return m


def box(x0, y0, z0, x1, y1, z1, mat, name="box", parent=None):
	"""An axis-aligned box whose texture repeats once a block on every face."""
	me = bpy.data.meshes.new(name)
	bm = bmesh.new()
	bmesh.ops.create_cube(bm, size=1)
	bmesh.ops.scale(bm, vec=(x1 - x0, y1 - y0, z1 - z0), verts=bm.verts)
	bmesh.ops.translate(bm, vec=((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2), verts=bm.verts)
	uv = bm.loops.layers.uv.new()
	for f in bm.faces:
		n = f.normal
		for loop in f.loops:
			co = loop.vert.co
			a, b = (co.y, co.z) if abs(n.x) > 0.5 else (co.x, co.z) if abs(n.y) > 0.5 else (co.x, co.y)
			loop[uv].uv = (a, b)
	bm.to_mesh(me)
	bm.free()
	ob = bpy.data.objects.new(name, me)
	ob.data.materials.append(mat)
	scene.collection.objects.link(ob)
	if parent:
		ob.parent = parent
	return ob


# --- the camera's place first: the pickaxe is turned to face it
CAM = Vector((2.1, -3.6, 2.4))
AIM = Vector((0.05, 0.3, 0.42))
FORWARD = (AIM - CAM).normalized()
RIGHT = FORWARD.cross(Vector((0, 0, 1))).normalized()
UP = RIGHT.cross(FORWARD)

# --- the ore block in the middle, the weak point on its top face near the front right corner, where the pickaxe lands
ore = box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5, material("iron_ore", mc("iron_ore")), "ore")
STAR = (0.2, -0.18, 0.5)
star_mat = material("star", color=(1.0, 0.78, 0.25), emit=5.0)
# a four-pointed star like the in-game marker, a hair above the face
me = bpy.data.meshes.new("star")
verts = [(STAR[0], STAR[1], STAR[2] + 0.006)]
for i in range(8):
	a = math.pi / 2 + i * math.pi / 4
	r = 0.21 if i % 2 == 0 else 0.055
	verts.append((STAR[0] + math.cos(a) * r, STAR[1] + math.sin(a) * r, STAR[2] + 0.006))
me.from_pydata(verts, [], [(0, 1 + i, 1 + (i + 1) % 8) for i in range(8)])
star = bpy.data.objects.new("star", me)
star.data.materials.append(star_mat)
scene.collection.objects.link(star)
glow = bpy.data.objects.new("star_light", bpy.data.lights.new("star_light", "POINT"))
glow.data.energy = 25
glow.data.color = (1.0, 0.8, 0.35)
glow.location = (STAR[0], STAR[1], STAR[2] + 0.25)
scene.collection.objects.link(glow)

# sparks flying up off the point
spark = material("spark", color=(1.0, 0.75, 0.25), emit=6.0)
for _ in range(10):
	a = random.uniform(0, 2 * math.pi)
	r = random.uniform(0.15, 0.5)
	x, y, z = STAR[0] + math.cos(a) * r, STAR[1] + math.sin(a) * r * 0.7, STAR[2] + random.uniform(0.05, 0.45)
	s = random.uniform(0.012, 0.022)
	box(x - s, y - s, z - s, x + s, y + s, z + s, spark, "spark")

# --- the iron pickaxe, its item texture mirrored (head to the upper left, handle down to the lower right) and
# extruded, each opaque pixel a little cube, standing square to the camera so its outline reads at a glance
PX = 1.4 / 16
TIP = (2.5, 11.0)  # the striking end of the mirrored head, pointing down, in texture pixels
ROLL = 14  # degrees clockwise as seen from the camera: steepens the handle, the tip leans to the left
pick = bpy.data.objects.new("pickaxe", None)
scene.collection.objects.link(pick)
img = bpy.data.images.load(mc("iron_pickaxe"))
w, h = img.size
pixels = list(img.pixels)
colors = {}
for py in range(h):
	for px in range(w):
		i = ((h - 1 - py) * w + px) * 4  # Blender stores rows bottom-up
		r, g, b, a = pixels[i:i + 4]
		if a < 0.5:
			continue
		key = (round(r, 3), round(g, 3), round(b, 3))
		if key not in colors:
			colors[key] = material("pick_%d" % len(colors), color=key, emit=0.25)
		x0, z0 = (w - 1 - px - TIP[0]) * PX, -(py + 1 - TIP[1]) * PX
		box(x0, -PX / 2, z0, x0 + PX, PX / 2, z0 + PX, colors[key], "pick_px", parent=pick)
turn = Matrix.Rotation(math.radians(ROLL), 3, FORWARD)
pick.rotation_euler = Matrix((turn @ RIGHT, turn @ FORWARD, turn @ UP)).transposed().to_euler()
pick.location = (STAR[0], STAR[1], STAR[2] + 0.04)  # the tip just on the face, not sunk into it

# --- an oak tree behind on the left, tilted as it starts to fall
tree = bpy.data.objects.new("tree", None)
scene.collection.objects.link(tree)
log = material("oak_log", mc("oak_log"))
leaves = material("oak_leaves", mc("oak_leaves"), alpha=True, tint=(0.36, 0.72, 0.2))
for z in range(4):
	box(-0.5, -0.5, z, 0.5, 0.5, z + 1, log, "log", parent=tree)
for x in range(-2, 3):
	for y in range(-2, 3):
		for z in (3, 4):
			if abs(x) == 2 and abs(y) == 2 or x == 0 and y == 0 and z == 3:
				continue
			box(x - 0.5, y - 0.5, z, x + 0.5, y + 0.5, z + 1, leaves, "leaf", parent=tree)
for x, y in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
	box(x - 0.5, y - 0.5, 5, x + 0.5, y + 0.5, 6, leaves, "leaf", parent=tree)
tree.scale = (0.5,) * 3
tree.location = (-1.3, 1.6, -0.5)
tree.rotation_euler = (math.radians(-6), math.radians(-12), 0)

# --- light, background, camera: a 3/4 view from slightly above, the block filling the middle
sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
sun.data.energy = 3.0
sun.rotation_euler = (math.radians(50), math.radians(10), math.radians(-35))
scene.collection.objects.link(sun)
world = bpy.data.worlds.new("bg")
world.use_nodes = True
world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.03, 0.05, 0.11, 1)
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 1.0
scene.world = world

cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
cam.data.lens = 57
scene.collection.objects.link(cam)
scene.camera = cam
cam.location = CAM
target = bpy.data.objects.new("target", None)
target.location = AIM
scene.collection.objects.link(target)
c = cam.constraints.new("TRACK_TO")
c.target = target
c.track_axis = "TRACK_NEGATIVE_Z"
c.up_axis = "UP_Y"

# a soft bloom round the star and the sparks
scene.use_nodes = True
nt = scene.node_tree
for n in list(nt.nodes):
	nt.nodes.remove(n)
layers = nt.nodes.new("CompositorNodeRLayers")
glare = nt.nodes.new("CompositorNodeGlare")
glare.glare_type = "FOG_GLOW"
glare.quality = "HIGH"
glare.threshold = 1.0
glare.size = 7
out = nt.nodes.new("CompositorNodeComposite")
nt.links.new(layers.outputs["Image"], glare.inputs["Image"])
nt.links.new(glare.outputs["Image"], out.inputs["Image"])

engines = [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items]
scene.render.engine = "BLENDER_EEVEE_NEXT" if "BLENDER_EEVEE_NEXT" in engines else "BLENDER_EEVEE"
scene.view_settings.view_transform = "Standard"
scene.render.resolution_x = scene.render.resolution_y = 1024
scene.render.filepath = os.path.join(ROOT, "sketch.png")
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(ROOT, "icon_scene.blend"))
bpy.ops.render.render(write_still=True)
print("rendered", scene.render.filepath)
