"use strict";
// One document per completed reply. Android sends one data message with no message port.
// Only these two shipped scripts can load. No generated markup is returned to Android.
const paper = document.getElementById("paper");
const maxPixels = 6000000;
let pixelsLeft = maxPixels;
const results = [];
window.richMeta = i => {
    const item = results[i];
    return item ? [item.width, item.height, item.png.length] : (item ?? null);
};
window.richChunk = (i, offset) => results[i].png.slice(offset, offset + 65536);

function loadEngine(name) {
    return new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = name;
        script.onload = resolve;
        script.onerror = reject;
        document.head.append(script);
    });
}

async function raster(svg, width, height, ink, pixelRatio = 2) {
    if (!(width > 0 && height > 0 && width <= 8192 && height <= 8192)) throw Error("size");
    const scale = Math.min(pixelRatio, 1600 / width, 1400 / height);
    const w = Math.ceil(width * scale), h = Math.ceil(height * scale);
    if (w * h > pixelsLeft) throw Error("budget");
    pixelsLeft -= w * h;
    const output = svg.cloneNode(true);
    if (output.querySelector("script,iframe,object,embed,image,foreignObject:not(:empty)")) throw Error("unsupported element");
    output.querySelectorAll("foreignObject").forEach(n => n.remove());
    for (const node of [output, ...output.querySelectorAll("*")]) {
        for (const a of [...node.attributes]) {
            if (/^on/i.test(a.name) || (a.localName === "href" && !a.value.startsWith("#"))) node.removeAttributeNode(a);
        }
    }
    output.querySelectorAll("a").forEach(a => a.replaceWith(...a.childNodes));
    output.setAttribute("width", width);
    output.setAttribute("height", height);
    output.style.maxWidth = "none";
    output.style.color = ink;
    const xml = new XMLSerializer().serializeToString(output);
    if (xml.length > 1500000) throw Error("large image");
    const image = new Image();
    image.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(xml);
    await image.decode();
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    canvas.getContext("2d").drawImage(image, 0, 0, w, h);
    const png = canvas.toDataURL("image/png").split(",")[1];
    if (!png || png.length > 1500000) throw Error("large bitmap");
    return {width, height, png};
}

async function typeset(items, ink) {
    window.MathJax = {
        startup: {typeset: false},
        tex: {packages: ["base", "ams", "mhchem", "cancel"], tags: "all", maxBuffer: 4096, maxMacros: 512},
        options: {enableMenu: false}, svg: {fontCache: "local"}
    };
    await loadEngine("mathjax.js");
    await MathJax.startup.promise;
    const nodes = [];
    for (const [i, item] of items.entries()) {
        if (item.kind === "mermaid") continue;
        // These extensions are already shipped. A document cannot request another package.
        const tex = item.source.replace(/\\require\s*\{(?:mhchem|cancel)\}/g, "");
        if (tex.length > 2048 || /\\(?:require|href|url|class|style|cssId|includegraphics|html\w*)\b/.test(tex)) {
            results[i] = false;
            continue;
        }
        const block = item.kind === "display";
        const node = document.createElement("div");
        node.textContent = block ? "\\[" + tex + "\\]" : "\\(" + tex + "\\)";
        paper.append(node);
        nodes.push([i, node]);
    }
    // A single MathJax document resolves both forward and backward references across message blocks.
    await MathJax.typesetPromise([paper]);
    for (const [i, node] of nodes) {
        try {
            const svg = node.querySelector("mjx-container > svg");
            if (!svg || svg.querySelector('[data-mml-node="merror"]')) throw Error("formula");
            const box = svg.getBoundingClientRect();
            results[i] = await raster(svg, box.width, box.height, ink);
        } catch { results[i] = false; }
    }
    paper.replaceChildren();
}

const csvLabel = /^(?:"((?:[^"]|"")*)"|([^",]+))\s*,\s*(?:"((?:[^"]|"")*)"|([^",]+))\s*,\s*(\d+(?:\.\d+)?)\s*$/;
function diagramInput(source) {
    if (source.length > 8192 || source.split("\n").length > 120 ||
        /%%\{|^\s*---|<\s*\/?[a-z][\w:-]*(?:\s|\/?>)|(?:https?|data|javascript|file):|^\s*(?:click|linkStyle|style|classDef)\b|url\s*\(/im.test(source)) throw Error("diagram input");
    const lines = source.trim().split(/\r?\n/);
    const labels = new Map();
    if (/^architecture-beta\s*$/.test(lines[0])) {
        // This grammar accepts Unicode in quoted display labels. IDs and edges are untouched.
        return {source: lines.map(line => line.replace(/\[([^\]"\r\n]*[^\x00-\x7f][^\]"\r\n]*)\]/g,
            (_, label) => "[" + JSON.stringify(label) + "]")).join("\n"), labels};
    }
    if (/^sankey(?:-beta)?\s*$/.test(lines[0]) && /[^\x00-\x7f]/.test(source)) {
        // The upstream CSV lexer only accepts ASCII node names. Restore the labels as SVG text,
        // after Mermaid has calculated the flows. A recorded amount never becomes a node index.
        let prefix = "HydrionNode";
        while (source.includes(prefix)) prefix += "_";
        const aliases = new Map();
        function name(value) {
            if (!aliases.has(value)) {
                const id = prefix + aliases.size;
                aliases.set(value, id);
                labels.set(id, value);
            }
            return aliases.get(value);
        }
        const rows = lines.slice(1).filter(line => line.trim()).map(line => {
            const m = csvLabel.exec(line.trim());
            if (!m) throw Error("sankey row");
            const from = (m[1] ?? m[2]).replace(/""/g, '"').trim();
            const to = (m[3] ?? m[4]).replace(/""/g, '"').trim();
            return [name(from), name(to), m[5]].join(",");
        });
        return {source: [lines[0], ...rows].join("\n"), labels};
    }
    return {source, labels};
}

async function diagrams(items, dark, ink) {
    await loadEngine("mermaid.js");
    mermaid.initialize({startOnLoad: false, securityLevel: "strict", suppressErrorRendering: true,
        maxTextSize: 8192, maxEdges: 120, theme: dark ? "dark" : "default", htmlLabels: false,
        flowchart: {htmlLabels: false}, fontFamily: "sans-serif"});
    for (const [i, item] of items.entries()) {
        if (item.kind !== "mermaid") continue;
        try {
            const input = diagramInput(item.source);
            const rendered = await mermaid.render("diagram" + i, input.source, paper);
            const svg = new DOMParser().parseFromString(rendered.svg, "image/svg+xml").documentElement;
            if (svg.localName !== "svg" || svg.querySelector("parsererror")) throw Error("diagram");
            // Only the inert, engine-generated SVG enters the raster stage. Never bind its callbacks.
            if (input.labels.size) {
                const walker = document.createTreeWalker(svg, NodeFilter.SHOW_TEXT);
                for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                    if (node.parentElement?.closest("text,title")) {
                        node.nodeValue = node.nodeValue.replace(/HydrionNode_*\d+/g, key => input.labels.get(key) ?? key);
                    }
                }
            }
            const b = svg.viewBox.baseVal;
            results[i] = await raster(svg, b.width, b.height, ink, 1.5);
        } catch { results[i] = false; }
        paper.replaceChildren();
    }
}

window.addEventListener("message", async event => {
    if (typeof event.data !== "string" || event.data.length > 150000) return;
    const job = JSON.parse(event.data);
    if (!Array.isArray(job.items) || job.items.length > 48 ||
        !job.items.every(x => x && ["inline","display","mermaid"].includes(x.kind) && typeof x.source === "string")) return;
    const ink = /^#[a-f0-9]{6}$/i.test(job.ink) ? job.ink : "#202124";
    paper.style.width = Math.min(800, Math.max(120, Number(job.width) || 320)) + "px";
    paper.style.color = ink;
    if (job.items.some(x => x.kind !== "mermaid")) {
        try { await typeset(job.items, ink); } catch { paper.replaceChildren(); }
    }
    if (job.items.some(x => x.kind === "mermaid")) {
        try { await diagrams(job.items, job.dark === true, ink); } catch { paper.replaceChildren(); }
    }
    for (let i = 0; i < job.items.length; i++) if (results[i] == null) results[i] = false;
}, {once: true});
