#!/usr/bin/env python3
"""Build SMPClassSkins resource pack for Minecraft Java 1.21.11 (stdlib only)."""
import json, struct, zlib, sys
from pathlib import Path

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "target/SMPClassSkins-ResourcePack")
NS = "smpskins"
SKINS = {
    "ruby_edge": ("sword", True, (175, 30, 48)),
    "malachite_edge": ("sword", False, (34, 168, 104)),
    "frostfang": ("sword", True, (95, 204, 244)),
    "ember_cleaver": ("axe", True, (235, 93, 34)),
    "woodland_hatchet": ("axe", False, (132, 92, 52)),
    "void_drill": ("pickaxe", True, (111, 50, 181)),
    "crystal_pick": ("pickaxe", False, (87, 215, 202)),
    "rune_shovel": ("shovel", True, (217, 176, 89)),
    "phantom_bow": ("bow", True, (112, 77, 166)),
    "harvest_scythe": ("hoe", True, (119, 174, 72)),
    "amethyst_crossbow": ("crossbow", True, (151, 83, 205)),
    "storm_trident": ("trident", True, (61, 157, 230)),
    "obsidian_mace": ("mace", True, (92, 75, 116)),
    "warden_shield": ("shield", True, (69, 107, 112)),
    "royal_armor": ("armor", True, (220, 175, 70)),
    "frost_armor": ("armor", True, (104, 196, 231)),
    "infernal_armor": ("armor", True, (209, 66, 40)),
    "ranger_armor": ("armor", False, (97, 156, 80)),
}

def png(path, w, h, pixels):
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw.extend(pixels[y][x])
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    data = b"\x89PNG\r\n\x1a\n"
    data += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    data += chunk(b"IEND", b"")
    path.write_bytes(data)

def canvas(w, h):
    return [[(0, 0, 0, 0) for _ in range(w)] for _ in range(h)]

def rect(p, x1, y1, x2, y2, color):
    h, w = len(p), len(p[0])
    for y in range(max(0, y1), min(h, y2)):
        for x in range(max(0, x1), min(w, x2)):
            p[y][x] = (*color, 255)

def line(p, x1, y1, x2, y2, color, width=1):
    dx, dy = abs(x2-x1), abs(y2-y1)
    sx = 1 if x1 < x2 else -1
    sy = 1 if y1 < y2 else -1
    err = dx - dy
    while True:
        rect(p, x1-width//2, y1-width//2, x1+(width+1)//2, y1+(width+1)//2, color)
        if x1 == x2 and y1 == y2: break
        e2 = 2*err
        if e2 > -dy: err -= dy; x1 += sx
        if e2 < dx: err += dx; y1 += sy

def icon_texture(kind, rgb, path):
    p = canvas(32, 32); base = rgb
    light = tuple(min(255, int(c*1.32+25)) for c in base)
    dark = tuple(max(0, int(c*0.48)) for c in base)
    shadow = (28, 27, 40)
    if kind == "sword":
        line(p,7,25,25,7,shadow,7); line(p,7,25,25,7,base,4); line(p,9,21,25,7,light,1)
        line(p,5,27,11,21,(116,77,50),5); line(p,11,22,15,26,light,3)
    elif kind == "axe":
        line(p,8,27,24,6,(119,79,49),4); rect(p,3,5,17,15,dark); rect(p,5,4,16,13,base); rect(p,7,3,15,8,light); rect(p,3,11,10,18,dark)
    elif kind == "pickaxe":
        line(p,8,27,23,8,(116,78,49),4); line(p,4,7,28,5,shadow,7); line(p,4,7,28,5,base,4); line(p,7,6,22,5,light,1)
    elif kind == "shovel":
        line(p,10,28,22,7,(118,78,48),4); rect(p,17,2,28,12,dark); rect(p,19,3,26,10,base); rect(p,20,3,24,5,light)
    elif kind == "bow":
        line(p,8,3,5,16,dark,4); line(p,5,16,8,29,dark,4); line(p,8,3,5,16,base,2); line(p,5,16,8,29,base,2)
        line(p,10,4,16,16,light,1); line(p,16,16,10,28,light,1); line(p,10,4,10,28,(225,222,210),1)
    elif kind == "crossbow":
        rect(p,5,10,27,17,shadow); rect(p,7,11,25,15,base); line(p,5,8,16,14,light,2); line(p,27,8,16,14,light,2)
        rect(p,14,15,18,28,(125,86,55)); rect(p,9,8,23,10,light)
    elif kind == "trident":
        line(p,16,28,16,4,base,3); line(p,16,5,7,1,light,2); line(p,16,5,25,1,light,2); line(p,16,5,16,0,light,2); rect(p,13,15,19,19,dark)
    elif kind == "mace":
        line(p,16,29,16,10,(127,84,54),4); rect(p,7,2,25,14,dark); rect(p,9,1,23,12,base); rect(p,12,0,20,4,light); rect(p,5,5,9,10,light)
    elif kind == "shield":
        rect(p,6,3,26,27,dark); rect(p,8,4,24,24,base)
        for i in range(6): rect(p,10+i*2,7+i,12+i*2,9+i,light)
        rect(p,14,7,18,21,light); rect(p,10,12,22,16,light)
    elif kind == "hoe":
        line(p,8,27,23,8,(116,78,49),4); rect(p,7,3,25,8,dark); rect(p,12,1,26,5,base); rect(p,18,1,24,3,light)
    else:
        rect(p,4,5,28,28,dark); rect(p,7,3,25,27,base); rect(p,10,6,22,10,light); rect(p,10,16,22,19,light); rect(p,12,23,20,25,dark)
    png(path, 32, 32, p)

def armor_texture(rgb, path, leggings=False):
    p=canvas(64,32); hi=tuple(min(255,c+55) for c in rgb); lo=tuple(max(0,c-60) for c in rgb)
    if not leggings:
        rect(p,8,1,24,15,lo); rect(p,10,3,22,13,rgb)
        rect(p,18,17,30,31,lo); rect(p,20,18,28,30,rgb)
        rect(p,40,17,48,31,lo); rect(p,42,18,46,30,rgb)
        rect(p,2,17,10,31,lo); rect(p,4,18,8,30,rgb)
        rect(p,20,19,28,21,hi); rect(p,42,19,46,21,hi); rect(p,4,19,8,21,hi); rect(p,11,5,21,7,hi); rect(p,18,26,30,28,hi)
    else:
        rect(p,0,17,16,31,lo); rect(p,2,18,14,30,rgb); rect(p,16,17,32,31,lo); rect(p,18,18,30,30,rgb)
        rect(p,34,17,40,31,lo); rect(p,42,17,48,31,lo); rect(p,36,18,38,30,rgb); rect(p,44,18,46,30,rgb)
        rect(p,2,20,14,22,hi); rect(p,18,20,30,22,hi)
    for x in range(0,64,8):
        for y in (18,29):
            if p[y][x][3]: p[y][x]=(*hi,255)
    png(path,64,32,p)

def face():
    return {"uv":[0,0,32,32],"texture":"#all"}

def cube(frm,to):
    frm=[v/2 for v in frm]; to=[v/2 for v in to]
    return {"from":frm,"to":to,"faces":{k:face() for k in ("north","south","east","west","up","down")}}

def item_model(kind, texture_id):
    transforms = {
        "thirdperson_righthand":{"rotation":[0,45,0],"translation":[0,2,0],"scale":[0.85,0.85,0.85]},
        "thirdperson_lefthand":{"rotation":[0,45,0],"translation":[0,2,0],"scale":[0.85,0.85,0.85]},
        "firstperson_righthand":{"rotation":[0,-45,20],"translation":[1.2,2.5,0.5],"scale":[0.9,0.9,0.9]},
        "firstperson_lefthand":{"rotation":[0,135,-20],"translation":[1.2,2.5,0.5],"scale":[0.9,0.9,0.9]},
        "gui":{"rotation":[25,135,0],"translation":[0,0,0],"scale":[1,1,1]},
        "ground":{"translation":[0,3,0],"scale":[0.65,0.65,0.65]},
        "fixed":{"rotation":[0,180,0],"scale":[0.8,0.8,0.8]}
    }
    if kind=="sword": elements=[cube([7,1,7],[9,21,9]),cube([6,3,7],[10,19,9]),cube([4,20,6],[12,22,10]),cube([7,22,7],[9,29,9]),cube([6,28,6],[10,31,10])]
    elif kind=="axe": elements=[cube([7,2,7],[9,30,9]),cube([2,4,6],[10,14,10]),cube([2,4,5],[7,15,11]),cube([4,2,6],[11,6,10])]
    elif kind=="pickaxe": elements=[cube([7,6,7],[9,30,9]),cube([1,2,6],[31,6,10]),cube([1,2,5],[6,8,11]),cube([26,2,5],[31,8,11])]
    elif kind=="shovel": elements=[cube([7,7,7],[9,30,9]),cube([4,1,5],[12,10,11]),cube([5,1,6],[11,5,10])]
    elif kind=="bow": elements=[cube([7,2,7],[9,14,9]),cube([5,1,7],[7,5,9]),cube([5,11,7],[7,15,9]),cube([8,13,7],[10,15,9])]
    elif kind=="crossbow": elements=[cube([5,8,7],[27,14,9]),cube([7,5,6],[10,20,10]),cube([22,5,6],[25,20,10]),cube([13,13,5],[19,25,11]),cube([13,4,6],[19,8,10])]
    elif kind=="trident": elements=[cube([15,3,7],[17,30,9]),cube([8,1,6],[10,9,10]),cube([22,1,6],[24,9,10]),cube([15,0,6],[17,7,10])]
    elif kind=="mace": elements=[cube([14,2,7],[18,25,9]),cube([8,20,5],[24,30,11]),cube([10,18,4],[22,31,12]),cube([12,22,3],[20,29,13])]
    elif kind=="shield": elements=[cube([4,2,7],[28,29,9]),cube([7,5,6],[25,26,10]),cube([14,9,5],[18,22,11])]
    elif kind=="hoe": elements=[cube([7,7,7],[9,30,9]),cube([4,2,5],[28,6,11]),cube([23,2,4],[28,8,12])]
    else: return {"parent":"minecraft:item/generated","textures":{"layer0":f"{NS}:item/{texture_id}"}}
    return {"textures":{"all":f"{NS}:item/{texture_id}"},"elements":elements,"display":transforms}

def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")

def main():
    if OUT.exists():
        import shutil
        shutil.rmtree(OUT)
    assets=OUT/"assets"/NS
    write_json(OUT/"pack.mcmeta",{"pack":{"description":"SMPClassSkins • Java 1.21.11","min_format":[75,0],"max_format":[75,0]}})
    icon=canvas(128,128); rect(icon,8,8,120,120,(26,24,36)); rect(icon,12,12,116,116,(69,52,35)); rect(icon,18,18,110,110,(22,26,37))
    line(icon,38,91,92,37,(222,179,78),13); line(icon,38,91,92,37,(100,207,216),6); line(icon,26,103,48,81,(151,94,54),12)
    png(OUT/"pack.png",128,128,icon)
    for sid,(kind,is3d,rgb) in SKINS.items():
        icon_texture(kind,rgb,assets/"textures"/"item"/f"{sid}.png")
        model_ref=f"{NS}:item/skins/{sid}"
        definition={"type":"minecraft:model","model":model_ref}
        if kind=="bow":
            definition={"type":"minecraft:condition","property":"minecraft:using_item",
                "on_false":{"type":"minecraft:model","model":model_ref},
                "on_true":{"type":"minecraft:range_dispatch","property":"minecraft:use_duration","scale":0.05,
                    "entries":[{"threshold":0.65,"model":{"type":"minecraft:model","model":f"{model_ref}_pull1"}},
                              {"threshold":0.9,"model":{"type":"minecraft:model","model":f"{model_ref}_pull2"}}],
                    "fallback":{"type":"minecraft:model","model":f"{model_ref}_pull0"}}}
        write_json(assets/"items"/"skin"/f"{sid}.json",{"model":definition})
        model=item_model(kind,sid) if is3d and kind!="armor" else {"parent":"minecraft:item/generated","textures":{"layer0":f"{NS}:item/{sid}"}}
        write_json(assets/"models"/"item"/"skins"/f"{sid}.json",model)
        if kind=="bow":
            for stage in range(3): write_json(assets/"models"/"item"/"skins"/f"{sid}_pull{stage}.json",model)
        if kind=="armor":
            equip_id={"royal_armor":"royal","frost_armor":"frost","infernal_armor":"infernal","ranger_armor":"ranger"}[sid]
            write_json(assets/"equipment"/f"{equip_id}.json",{"layers":{"humanoid":[{"texture":f"{NS}:{equip_id}"}],"humanoid_leggings":[{"texture":f"{NS}:{equip_id}"}]}})
            armor_texture(rgb,assets/"textures"/"entity"/"equipment"/"humanoid"/f"{equip_id}.png")
            armor_texture(rgb,assets/"textures"/"entity"/"equipment"/"humanoid_leggings"/f"{equip_id}.png",True)
    (OUT/"README.txt").write_text(
        "SMPClassSkins resource pack for Minecraft Java 1.21.11\n\n"
        "Install the ZIP in the client resourcepacks folder or host it and set server.properties resource-pack.\n"
        "Install SMPClassSkins-1.0.0.jar on the Paper/Purpur 1.21.11 server.\n"
        "Use /case to open a case (1 diamond) and /skin to equip unlocked skins.\n"
        "Includes 2D item textures, voxel-style 3D models, bow pull stages and custom armor equipment textures.\n",
        encoding="utf-8")
    print(f"Built resource pack at {OUT} with {len(SKINS)} skins")

if __name__=="__main__": main()
