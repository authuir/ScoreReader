module.exports = {
  presets: [[
    '@babel/preset-env',
    {
      targets: { chrome: '44' },
      // We don't need symbol-friendly module output; OSMD bundle is a UMD script.
      modules: false,
      // Use loose mode for smaller / faster output where compatibility is fine.
      loose: true,
      // Don't inject any polyfills; we manage compatibility separately.
      useBuiltIns: false,
      bugfixes: true
    }
  ]],
  compact: true,
  comments: false,
  sourceMaps: false
};
