// Converts the diagram specs in ./specs/*.json into native Excalidraw files in ../<name>.excalidraw.
//
// A spec is the compact element list the Excalidraw MCP (create_view) also renders:
//   - shapes may carry a `label` ({ text, fontSize }) instead of a separate text element,
//   - arrows may carry `startBinding` / `endBinding` with only an `elementId`,
//   - `cameraUpdate` / `delete` / `restoreCheckpoint` entries only steer the MCP view and are skipped here.
// Edit a spec, then run:  node docs/architecture/diagrams/generators/generate.mjs
//
// Shared style (same in every spec): «web-api» blue, «scheduled-job» orange, «kafka-consumer» purple,
// «database» green ellipse, «cache» pink ellipse, «topic» yellow square box, «external» grey dashed,
// «edge-infra» teal; pods are dashed outlines; zones are faint filled areas.

import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, basename } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const specsDir = join(here, "specs");
const outDir = join(here, "..");

const MCP_ONLY_TYPES = ["cameraUpdate", "delete", "restoreCheckpoint"];
const LINE_HEIGHT = 1.25;

let seedCounter = 1;
function nextSeed() {
  seedCounter = seedCounter + 1;
  return seedCounter * 7919;
}

function base(spec) {
  return {
    id: spec.id,
    type: spec.type,
    x: spec.x,
    y: spec.y,
    width: spec.width,
    height: spec.height,
    angle: 0,
    strokeColor: spec.strokeColor || "#1e1e1e",
    backgroundColor: spec.backgroundColor || "transparent",
    fillStyle: spec.fillStyle || "solid",
    strokeWidth: spec.strokeWidth || 2,
    strokeStyle: spec.strokeStyle || "solid",
    roughness: 1,
    opacity: spec.opacity || 100,
    groupIds: [],
    frameId: null,
    roundness: spec.roundness || null,
    seed: nextSeed(),
    version: 1,
    versionNonce: nextSeed(),
    isDeleted: false,
    boundElements: [],
    updated: 1,
    link: null,
    locked: false,
  };
}

function measure(text, fontSize) {
  const lines = text.split("\n");
  let longest = 0;
  for (const line of lines) {
    if (line.length > longest) {
      longest = line.length;
    }
  }
  return {
    width: Math.ceil(longest * fontSize * 0.55),
    height: Math.ceil(lines.length * fontSize * LINE_HEIGHT),
  };
}

function textElement(id, text, fontSize, centerX, centerY, containerId, color) {
  const size = measure(text, fontSize);
  const el = base({
    id: id,
    type: "text",
    x: centerX - size.width / 2,
    y: centerY - size.height / 2,
    width: size.width,
    height: size.height,
    strokeColor: color,
  });
  el.backgroundColor = "transparent";
  el.boundElements = null;
  el.text = text;
  el.originalText = text;
  el.fontSize = fontSize;
  el.fontFamily = 2; // Helvetica (1 = Virgil, the handwritten default)
  el.textAlign = "center";
  el.verticalAlign = "middle";
  el.containerId = containerId;
  el.lineHeight = LINE_HEIGHT;
  el.autoResize = true;
  return el;
}

// Point in the middle of an arrow's path, where Excalidraw draws its label.
function arrowMiddle(spec) {
  const points = spec.points;
  if (points.length === 2) {
    return {
      x: spec.x + (points[0][0] + points[1][0]) / 2,
      y: spec.y + (points[0][1] + points[1][1]) / 2,
    };
  }
  const middle = points[Math.floor(points.length / 2)];
  return { x: spec.x + middle[0], y: spec.y + middle[1] };
}

function convert(specElements) {
  const out = [];
  const byId = {};

  for (const spec of specElements) {
    if (MCP_ONLY_TYPES.includes(spec.type)) {
      continue;
    }

    if (spec.type === "text") {
      const size = measure(spec.text, spec.fontSize || 20);
      const el = textElement(spec.id, spec.text, spec.fontSize || 20, 0, 0, null, spec.strokeColor || "#1e1e1e");
      el.x = spec.x;
      el.y = spec.y;
      el.width = size.width;
      el.height = size.height;
      el.textAlign = "left";
      el.verticalAlign = "top";
      out.push(el);
      byId[el.id] = el;
      continue;
    }

    const el = base(spec);
    if (spec.type === "arrow") {
      el.points = spec.points;
      el.lastCommittedPoint = null;
      el.startArrowhead = spec.startArrowhead || null;
      el.endArrowhead = spec.endArrowhead === undefined ? "arrow" : spec.endArrowhead;
      el.elbowed = false;
      el.startBinding = null;
      el.endBinding = null;
      if (spec.startBinding) {
        el.startBinding = { elementId: spec.startBinding.elementId, focus: 0, gap: 4 };
      }
      if (spec.endBinding) {
        el.endBinding = { elementId: spec.endBinding.elementId, focus: 0, gap: 4 };
      }
    }
    out.push(el);
    byId[el.id] = el;

    if (spec.label) {
      const fontSize = spec.label.fontSize || 20;
      let center;
      if (spec.type === "arrow") {
        center = arrowMiddle(spec);
      } else {
        center = { x: spec.x + spec.width / 2, y: spec.y + spec.height / 2 };
      }
      const label = textElement(spec.id + "_label", spec.label.text, fontSize, center.x, center.y, spec.id, "#1e1e1e");
      el.boundElements.push({ id: label.id, type: "text" });
      out.push(label);
      byId[label.id] = label;
    }
  }

  // Tell each bound shape which arrows are attached to it.
  for (const el of out) {
    if (el.type !== "arrow") {
      continue;
    }
    const ends = [el.startBinding, el.endBinding];
    for (const binding of ends) {
      if (binding === null) {
        continue;
      }
      const target = byId[binding.elementId];
      if (target === undefined) {
        throw new Error("arrow " + el.id + " is bound to unknown element " + binding.elementId);
      }
      target.boundElements.push({ id: el.id, type: "arrow" });
    }
  }
  return out;
}

const specFiles = readdirSync(specsDir).filter((name) => name.endsWith(".json")).sort();
for (const file of specFiles) {
  const specElements = JSON.parse(readFileSync(join(specsDir, file), "utf8"));
  const scene = {
    type: "excalidraw",
    version: 2,
    source: "docs/architecture/diagrams/generators/generate.mjs",
    elements: convert(specElements),
    appState: { viewBackgroundColor: "#ffffff", gridSize: null },
    files: {},
  };
  const outFile = join(outDir, basename(file, ".json") + ".excalidraw");
  writeFileSync(outFile, JSON.stringify(scene, null, 2) + "\n");
  console.log("wrote " + outFile + " (" + scene.elements.length + " elements)");
}
