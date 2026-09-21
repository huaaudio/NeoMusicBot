// A source-controlled caller supplies one hash-verified font; no host font is used.
const assert = require('node:assert/strict');
const canvas = require(process.argv[2]);
canvas.registerFont(process.argv[3], { family: 'NeoProbe' });
try {
    const page = canvas.createCanvas(512, 80);
    const painter = page.getContext('2d');
    painter.font = '32px NeoProbe';
    const wide = painter.measureText('MMMMMMMM').width;
    const narrow = painter.measureText('iiiiiiii').width;
    assert(narrow > 0 && wide > narrow * 1.5 && wide < 512, 'Expected proportional font metrics');
    painter.fillText('NeoMusicBot ffi العربية', 8, 50);
    const pixels = painter.getImageData(0, 0, 512, 80).data;
    let ink = 0;
    for (let index = 3; index < pixels.length; index += 4) if (pixels[index] > 0) ink++;
    assert(ink > 100, 'Text rendering produced no useful glyph pixels');
    console.log('canvas.font=passed');
} finally {
    canvas.deregisterAllFonts();
}
