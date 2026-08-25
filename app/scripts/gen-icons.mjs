import zlib from "node:zlib";
import fs from "node:fs";
import path from "node:path";

function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, "ascii");
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}

// Draws a simple Lucy Live mark: accent background, rounded inner panel, "L".
function makePng(size) {
  const bg = [0x12, 0x12, 0x1c];
  const accent = [0x7c, 0x5c, 0xff];
  const light = [0xe8, 0xe8, 0xf0];
  const raw = Buffer.alloc((size * 4 + 1) * size);
  const cx = size / 2;
  const cy = size / 2;
  const r = size * 0.42;
  for (let y = 0; y < size; y++) {
    const off = y * (size * 4 + 1);
    raw[off] = 0;
    for (let x = 0; x < size; x++) {
      const p = off + 1 + x * 4;
      const dx = x - cx;
      const dy = y - cy;
      const inside = dx * dx + dy * dy <= r * r;
      const col = inside ? accent : bg;
      // crude "L": vertical bar on left third, bottom bar
      let on = false;
      const lx0 = cx - r * 0.45, lx1 = cx - r * 0.1;
      const ly0 = cy - r * 0.5, ly1 = cy + r * 0.5;
      const bx0 = cx - r * 0.45, bx1 = cx + r * 0.45;
      const by0 = cy + r * 0.25, by1 = cy + r * 0.5;
      if (inside && ((x >= lx0 && x <= lx1 && y >= ly0 && y <= ly1) ||
                     (x >= bx0 && x <= bx1 && y >= by0 && y <= by1))) on = true;
      const c = on ? light : col;
      raw[p] = c[0]; raw[p + 1] = c[1]; raw[p + 2] = c[2]; raw[p + 3] = 255;
    }
  }
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 6; // 8-bit RGBA
  const idat = zlib.deflateSync(raw);
  return Buffer.concat([sig, chunk("IHDR", ihdr), chunk("IDAT", idat), chunk("IEND", Buffer.alloc(0))]);
}

const out = path.resolve(process.argv[2] || ".");
fs.writeFileSync(path.join(out, "icon-192.png"), makePng(192));
fs.writeFileSync(path.join(out, "icon-512.png"), makePng(512));
console.log("wrote icon-192.png, icon-512.png to", out);
