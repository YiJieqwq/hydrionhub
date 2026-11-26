"""Run with Python Playwright and an installed Chromium. All page requests use local assets."""
import base64
import gzip
import os
from pathlib import Path
import shutil
from playwright.sync_api import sync_playwright

ASSETS = Path(__file__).resolve().parents[2] / "main/assets/rich"
ORIGIN = "https://rich.hydrion.invalid"
NAMES = {"index.html", "render.js", "mathjax.js", "mermaid.js"}


def render(browser, items):
    page = browser.new_page(viewport={"width": 360, "height": 760})
    blocked = []

    def serve(route):
        url = route.request.url
        name = url.rsplit("/", 1)[-1]
        if name not in NAMES or url != ORIGIN + "/" + name:
            blocked.append(url)
            route.abort()
            return
        file = ASSETS / name
        data = file.read_bytes() if file.exists() else gzip.decompress((ASSETS / (name + ".gz")).read_bytes())
        route.fulfill(body=data, content_type="text/html" if name == "index.html" else "application/javascript")

    page.route("**/*", serve)
    page.goto(ORIGIN + "/index.html")
    # Observe the diagrams and glyphs that actually reach the rasterizer.
    page.evaluate("""() => {
      window.drawn = [];
      const original = XMLSerializer.prototype.serializeToString;
      XMLSerializer.prototype.serializeToString = function(node) {
        if (node.localName === 'svg') drawn.push({
          labels: [...node.querySelectorAll('text,tspan')].map(n => n.textContent),
          glyphs: [...node.querySelectorAll('[data-c]')].map(n => n.getAttribute('data-c')),
          shapes: node.querySelectorAll('path,line,rect').length
        });
        return original.call(this,node);
      };
    }""")
    page.evaluate("items => window.postMessage(JSON.stringify({items, width:340, ink:'#202428'}), location.origin)",
                  [{"kind": k, "source": s} for k, s in items])
    page.wait_for_function("n => Array.from({length:n}, (_, i) => richMeta(i)).every(x => x !== null)",
                           arg=len(items), timeout=45000)
    metadata = page.evaluate("n => Array.from({length:n}, (_,i) => richMeta(i))", len(items))
    for i, meta in enumerate(metadata):
        if meta:
            chunks = page.evaluate("([i,n]) => Array.from({length:Math.ceil(n/65536)}, (_,k) => richChunk(i,k*65536))",
                                   [i, meta[2]])
            assert len("".join(chunks)) == meta[2]
            assert base64.b64decode("".join(chunks), validate=True).startswith(b"\x89PNG\r\n\x1a\n")
    drawn = page.evaluate("window.drawn")
    page.close()
    assert not blocked
    return metadata, drawn


def smoke(browser):
    items = [("inline", r"\eqref{eq:sample}"), ("display", r"y=x\label{eq:sample}"),
             ("inline", r"\ce{H2O}"), ("inline", r"\require{cancel}\cancel{x}"),
             ("inline", r"\frac{1}{2}"), ("inline", r"\unknownGardenCommand"),
             ("mermaid", "architecture-beta\ngroup g(cloud)[花园]\nservice a(server)[书架] in g"),
             ("mermaid", "sankey-beta\n花园,书房,7\n书房,画室,3"), ("mermaid", "unrecognized diagram")]
    meta, svg = render(browser, items)
    assert [bool(m) for m in meta] == [True] * 5 + [False, True, True, False]
    assert svg[0]["glyphs"] == ["28", "31", "29"]  # forward reference to equation (1)
    assert all(s["shapes"] for s in svg)
    assert "花园" in svg[-2]["labels"] and "书架" in svg[-2]["labels"]
    assert set(svg[-1]["labels"]) == {"花园\n7", "书房\n7", "画室\n3"}
    _, next_message = render(browser, [("display", "z=2")])
    assert next_message[0]["glyphs"][-3:] == ["28", "31", "29"]
    print("10 synthetic items passed, including two expected source fallbacks and fresh message numbering.")


if __name__ == "__main__":
    with sync_playwright() as playwright:
        with playwright.chromium.launch(executable_path=os.environ.get("CHROMIUM") or shutil.which("chromium"),
                                       args=["--disable-background-networking"]) as browser:
            smoke(browser)
