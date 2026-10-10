# Architecture diagrams (Excalidraw)

Every diagram here is a native Excalidraw file with one shared style. To view or edit one, open
[excalidraw.com](https://excalidraw.com) → Open → choose the file (GitHub can't render `.excalidraw` inline).

Style (same in every diagram): «web-api» blue · «scheduled-job» orange · «kafka-consumer» purple · «database» green
ellipse · «cache» pink ellipse · «topic» yellow square box · «external» grey dashed · «edge-infra» teal · pods are
dashed outlines · zones are faint filled areas.

| Diagram | File | What it shows | Text version |
|---|---|---|---|
| L2 — System topology | [l2-topology.excalidraw](diagrams/l2-topology.excalidraw) | edge cell → central cluster → Kafka → consumers; numbered edges 1→13 tell the flow | [architecture.md](architecture.md) "L2 — System Topology" |
| Authorization flow | [authorization-flow.excalidraw](diagrams/authorization-flow.excalidraw) | the demo PCI-compliant checkout page: create + authorize, what the caller gets back | [architecture.md](architecture.md) "Authorization Flow" |
| Cell routing (Stage 1B) | [cell-routing.excalidraw](diagrams/cell-routing.excalidraw) | the NGINX Snowflake-aware Lua router: an intent is always served by the cell that created it | [architecture.md](architecture.md) "Stage 1B: Snowflake-Aware Ingress Routing" |
| L3 — PspResultConsumer branches | [l3-psp-result-branches.excalidraw](diagrams/l3-psp-result-branches.excalidraw) | the four branches inside `payment-consumers` (authorized, capture confirmed, internal transfer, settlement), each one commit | [architecture.md](architecture.md) "L3 — PspResultConsumer branches" |
| How a payment evolves | [payment-lifecycle.excalidraw](diagrams/payment-lifecycle.excalidraw) | the edge `PaymentIntent` state machine, the `payment_authorized` handover, the central `Payment` state machine and the journal each step posts | [domain-model.md](domain-model.md) §2.1 + §2.2 |
| Persistence model (ER) | [er-model.excalidraw](diagrams/er-model.excalidraw) | the tables per database (edge-db per cell, central-db), real foreign keys vs logical links | [architecture.md](architecture.md) "Persistence model (ER)" |

## Changing a diagram

Each `.excalidraw` file is **generated** from a spec, `diagrams/generators/specs/<name>.json`. Don't edit the
`.excalidraw` file: edit the spec and regenerate.

**What a spec is:** a JSON array of elements. Three kinds:

```json
[
  { "type": "text", "id": "title", "x": 20, "y": -10, "text": "My diagram", "fontSize": 28 },

  { "type": "rectangle", "id": "svc", "x": 100, "y": 100, "width": 220, "height": 60,
    "strokeColor": "#4a9eed", "backgroundColor": "#a5d8ff", "roundness": { "type": 3 },
    "label": { "text": "payment-service «web-api»", "fontSize": 16 } },

  { "type": "ellipse", "id": "db", "x": 100, "y": 260, "width": 220, "height": 70,
    "strokeColor": "#22c55e", "backgroundColor": "#b2f2bb",
    "label": { "text": "edge-db «database»", "fontSize": 16 } },

  { "type": "arrow", "id": "a1", "x": 210, "y": 160, "points": [[0, 0], [0, 100]], "width": 0, "height": 100,
    "startBinding": { "elementId": "svc" }, "endBinding": { "elementId": "db" },
    "label": { "text": "1 · INSERT intent", "fontSize": 14 } }
]
```

- **Shape** (`rectangle`, `ellipse`, `diamond`): `x`/`y` is the top-left corner, `width`/`height` its size; `label.text`
  is centred inside it (`\n` for a line break). `strokeStyle: "dashed"` for externals and pod outlines;
  `opacity: 35` for a zone (a big faint rectangle behind a group).
- **Arrow**: `x`/`y` is where it starts; `points` are offsets from there, `[[0,0], [dx,dy], ...]` (more than two points
  for a bent arrow); `width`/`height` is the span of the points. `startBinding`/`endBinding` name the shapes it joins
  (by `id`) so Excalidraw keeps it attached when a shape is moved. `label` is optional.
- **Text**: free text (titles, zone names). `x` is its left edge.
- Every `id` is unique in the file. Elements are drawn in array order: zones first, then shapes, then arrows.

**Colours** (the shared style; the same six pairs in every spec, stroke / fill):
«web-api» `#4a9eed` / `#a5d8ff` · «scheduled-job» `#f59e0b` / `#ffd8a8` · «kafka-consumer» `#8b5cf6` / `#d0bfff` ·
«database» `#22c55e` / `#b2f2bb` (ellipse) · «cache» `#ec4899` / `#eebefa` (ellipse) · «topic» `#ca8a04` / `#fff3bf` ·
«external» `#868e96` / `#e9ecef` dashed · «edge-infra» `#06b6d4` / `#c3fae8` · final / neutral `#495057` / `#e9ecef`.

**To add a box and an arrow**, e.g. to `l2-topology`:
1. Open `specs/l2-topology.json`, copy an existing shape of the same kind, give it a new `id`, position and label.
2. Add an arrow from/to it: copy an arrow, set `startBinding`/`endBinding` to the two ids, and set `x`/`y` to the
   start point on the first shape's edge and `points` so it ends on the second shape's edge.
3. Regenerate **that diagram only** and check it on excalidraw.com (Open → the `.excalidraw` file):
   ```bash
   node docs/architecture/diagrams/generators/generate.mjs l2-topology
   ```

**Why only that one:** the generator overwrites the `.excalidraw` file. A diagram that was changed by hand in
Excalidraw (moved, re-coloured, saved back) has those changes only in the `.excalidraw` file, so regenerating it would
throw them away. Running the generator without names regenerates **every** spec. `authorization-flow` is such a
hand-edited diagram: never regenerate it.

When the mermaid version in the text changes, change the spec too: the mermaid diagrams in `architecture.md` /
`domain-model.md` and these files describe the same thing.
