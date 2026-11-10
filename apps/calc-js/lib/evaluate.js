function evaluate(expr) {
  const src = expr.replace(/×/g, '*').replace(/÷/g, '/');
  if (!/^[0-9+\-*/%.() ]*$/.test(src)) throw new Error('invalid input');
  const v = Function('"use strict";return (' + src + ')')();
  if (!Number.isFinite(v)) throw new Error('not a number');
  return String(+v.toFixed(10));
}
module.exports = { evaluate };
