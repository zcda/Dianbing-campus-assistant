# Bundled browser dependencies

The application serves these files locally so the demonstration page loads without a public CDN.

- `vue.global.prod.js`: Vue 3.4.38, copied from the npm package `vue/dist/vue.global.prod.js`; license in `VUE-LICENSE.txt`.
- `marked.min.js`: Marked 12.0.2, copied from the npm package root; license in `MARKED-LICENSE.md`.

When updating either dependency, replace the asset and its license together, then verify `index.html` in a browser.
