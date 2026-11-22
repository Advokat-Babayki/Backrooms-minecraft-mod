#!/usr/bin/env python3
"""Generates the furniture block models, blockstates and item models.

Every model is authored with its front on the north side; blockstates rotate
it for the other three facings. Geometry lives here and not in 14 hand-written
JSON files, so a dimension tweak is one number. Run from the repo root.
"""
import json
import os

MODELS = "src/main/resources/assets/backrooms/models/block"
STATES = "src/main/resources/assets/backrooms/blockstates"
ITEMS = "src/main/resources/assets/backrooms/models/item"

FACES = ("down", "up", "north", "south", "west", "east")
ROTATIONS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def box(x0, y0, z0, x1, y1, z1, tex, **faces):
    """One cuboid; keyword faces override the texture of a single side."""
    return {
        "from": [x0, y0, z0],
        "to": [x1, y1, z1],
        "faces": {name: {"texture": faces.get(name, tex)} for name in FACES},
    }


def save(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    print("wrote", path)


def block_model(name, material, elements):
    textures = {"particle": f"backrooms:block/{material}"}
    for element in elements:
        for face in element["faces"].values():
            key = face["texture"]
            textures[key[1:]] = f"backrooms:block/{key[1:]}"
    save(os.path.join(MODELS, name + ".json"),
         {"parent": "minecraft:block/block", "textures": textures, "elements": elements})


def desk(wood, metal):
    tex = "#" + wood
    return [
        box(0, 14, 0, 16, 16, 16, tex),
        box(1, 0, 1, 3, 14, 3, tex),
        box(13, 0, 1, 15, 14, 3, tex),
        box(1, 0, 13, 3, 14, 15, tex),
        box(13, 0, 13, 15, 14, 15, tex),
        box(1, 4, 15, 15, 14, 16, tex),
        box(10, 0, 3, 15, 14, 14, tex),
        box(10.5, 1, 2, 14.5, 4, 3, tex),
        box(10.5, 5, 2, 14.5, 8, 3, tex),
        box(10.5, 9, 2, 14.5, 12, 3, tex),
        box(11, 2, 1, 13, 3, 2, "#" + metal),
        box(11, 6, 1, 13, 7, 2, "#" + metal),
        box(11, 10, 1, 13, 11, 2, "#" + metal),
    ]


def chair(wood, fabric):
    return [
        box(2, 0, 2, 4, 8, 4, "#" + wood),
        box(12, 0, 2, 14, 8, 4, "#" + wood),
        box(2, 0, 12, 4, 8, 14, "#" + wood),
        box(12, 0, 12, 14, 8, 14, "#" + wood),
        box(2, 8, 2, 14, 10, 14, "#" + fabric),
        box(2, 10, 12, 4, 16, 14, "#" + wood),
        box(12, 10, 12, 14, 16, 14, "#" + wood),
        box(4, 10, 12.5, 12, 16, 14, "#" + fabric),
    ]


def cabinet(drawers, metal, dark):
    heights = ([(1.5, 5.5), (6, 10), (10.5, 14.5)] if drawers == 3
               else [(1.5, 4.5), (5, 8), (8.5, 11.5), (12, 15)])
    elements = [
        box(1, 0, 2, 15, 15, 15, "#" + metal),
        box(1, 15, 1, 15, 16, 15, "#" + dark),
    ]
    for y0, y1 in heights:
        mid = (y0 + y1) / 2
        elements.append(box(2, y0, 1, 14, y1, 2, "#" + metal))
        elements.append(box(6, mid - 0.5, 0, 10, mid + 0.5, 1, "#" + dark))
    return elements


def shelf(filled, wood, paper, cardboard):
    elements = [
        box(1, 0, 1, 2, 16, 15, "#" + wood),
        box(14, 0, 1, 15, 16, 15, "#" + wood),
        box(2, 0, 14, 14, 16, 15, "#" + wood),
        box(2, 15, 1, 14, 16, 14, "#" + wood),
        box(2, 0, 1, 14, 1, 14, "#" + wood),
        box(2, 5, 1, 14, 6, 14, "#" + wood),
        box(2, 10, 1, 14, 11, 14, "#" + wood),
    ]
    if filled:
        elements += [
            box(3, 1, 3, 9, 5, 10, "#" + cardboard),
            box(10, 1.5, 3, 13, 4.5, 10, "#" + paper),
            box(3, 6, 4, 6, 10, 10, "#" + paper),
            box(8, 6, 4, 13, 9, 10, "#" + cardboard),
            box(3, 11, 3, 7, 15, 10, "#" + paper),
            box(8, 11, 3, 9, 15, 12, "#" + cardboard),
            box(10, 11, 3, 13, 14, 9, "#" + paper),
        ]
    return elements


def cooler(plastic, metal, water):
    return [
        box(4, 0, 4, 12, 8, 12, "#" + plastic),
        box(3, 8, 3, 13, 9, 13, "#" + metal),
        box(5, 9, 5, 11, 14, 11, "#" + water),
        box(7, 14, 7, 9, 15, 9, "#" + water),
        box(6, 15, 6, 10, 16, 10, "#" + plastic),
        box(6, 4, 3, 8, 6, 4, "#" + metal),
    ]


def plant_small(pot, soil, leaf):
    return [
        box(5, 0, 5, 11, 5, 11, "#" + pot),
        box(4, 4, 4, 12, 6, 12, "#" + pot),
        box(5, 5, 5, 11, 6, 11, "#" + soil),
        box(3, 6, 3, 13, 11, 13, "#" + leaf),
        box(5, 9, 5, 15, 14, 15, "#" + leaf),
        box(2, 7, 6, 5, 12, 10, "#" + leaf),
    ]


def plant_tall(pot, soil, leaf, trunk):
    return [
        box(5, 0, 5, 11, 5, 11, "#" + pot),
        box(4, 4, 4, 12, 6, 12, "#" + pot),
        box(5, 5, 5, 11, 6, 11, "#" + soil),
        box(7, 6, 7, 9, 13, 9, "#" + trunk),
        box(2, 11, 2, 14, 16, 14, "#" + leaf),
        box(4, 13, 4, 12, 16, 12, "#" + leaf),
        box(1, 12, 5, 6, 16, 11, "#" + leaf),
        box(10, 12, 5, 15, 16, 11, "#" + leaf),
    ]


def trash(metal, rim, paper):
    return [
        box(4, 0, 4, 12, 9, 12, "#" + metal),
        box(3, 9, 3, 13, 10, 13, "#" + rim),
        box(6, 10, 6, 10, 12, 10, "#" + paper),
    ]


def printer(plastic, dark, paper, screen):
    return [
        box(2, 0, 3, 14, 7, 14, "#" + plastic),
        box(2, 7, 2, 14, 8, 14, "#" + dark),
        box(3, 4, 0, 13, 5, 3, "#" + dark),
        box(4, 5, 0, 12, 5.5, 3, "#" + paper),
        box(4, 8, 4, 12, 9, 12, "#" + paper),
        box(3, 8, 2, 7, 9, 4, "#" + screen),
        box(4, 2, 2, 12, 4, 3, "#" + dark),
    ]


def directionals(name, models):
    variants = {}
    for index, model in enumerate(models):
        for facing, y in ROTATIONS:
            entry = {"model": f"backrooms:block/{model}"}
            if y:
                entry["y"] = y
            variants[f"facing={facing},variant={index}"] = entry
    save(os.path.join(STATES, name + ".json"), {"variants": variants})


def plain(name, model):
    variants = {}
    for facing, y in ROTATIONS:
        entry = {"model": f"backrooms:block/{model}"}
        if y:
            entry["y"] = y
        variants[f"facing={facing}"] = entry
    save(os.path.join(STATES, name + ".json"), {"variants": variants})


def main():
    # Desks: oak, spruce and dark oak, matching the planks they salvage into.
    for wood, model in (("wood_oak", "office_desk_oak"),
                        ("wood_spruce", "office_desk_spruce"),
                        ("wood_dark", "office_desk_dark")):
        block_model(model, wood, desk(wood, "metal_dark"))

    block_model("office_chair_black", "fabric_black", chair("wood_oak", "fabric_black"))
    block_model("office_chair_brown", "fabric_brown", chair("wood_dark", "fabric_brown"))
    block_model("filing_cabinet_3", "metal_light", cabinet(3, "metal_light", "metal_dark"))
    block_model("filing_cabinet_4", "metal_light", cabinet(4, "metal_light", "metal_dark"))
    block_model("office_shelf_empty", "wood_oak",
                shelf(False, "wood_oak", "paper_white", "cardboard"))
    block_model("office_shelf_filled", "wood_oak",
                shelf(True, "wood_oak", "paper_white", "cardboard"))
    block_model("water_cooler", "plastic_white",
                cooler("plastic_white", "metal_dark", "water_blue"))
    block_model("potted_plant_small", "terracotta",
                plant_small("terracotta", "soil", "leaf_green"))
    block_model("potted_plant_tall", "terracotta",
                plant_tall("terracotta", "soil", "leaf_green", "wood_dark"))
    block_model("trash_bin", "metal_dark", trash("metal_dark", "metal_light", "paper_white"))
    block_model("printer", "plastic_grey",
                printer("plastic_grey", "metal_dark", "paper_white", "screen_black"))

    directionals("office_desk", ["office_desk_oak", "office_desk_spruce", "office_desk_dark"])
    directionals("office_chair", ["office_chair_black", "office_chair_brown"])
    directionals("filing_cabinet", ["filing_cabinet_3", "filing_cabinet_4"])
    directionals("office_shelf", ["office_shelf_empty", "office_shelf_filled"])
    directionals("potted_plant", ["potted_plant_small", "potted_plant_tall"])
    plain("water_cooler", "water_cooler")
    plain("trash_bin", "trash_bin")
    plain("printer", "printer")

    for item, model in (
            ("office_desk", "office_desk_oak"),
            ("office_chair", "office_chair_black"),
            ("filing_cabinet", "filing_cabinet_3"),
            ("office_shelf", "office_shelf_empty"),
            ("water_cooler", "water_cooler"),
            ("potted_plant", "potted_plant_small"),
            ("trash_bin", "trash_bin"),
            ("printer", "printer")):
        save(os.path.join(ITEMS, item + ".json"), {"parent": f"backrooms:block/{model}"})


if __name__ == "__main__":
    main()
