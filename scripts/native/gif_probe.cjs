const assert = require('node:assert/strict');
const canvas = require(process.argv[2]);

(async () => {
    // Original 1x1 GIF fixture: red/black palette, one red pixel, no external data.
    const gif = Buffer.from('47494638396101000100800000ff00000000002c00000000010001000002024401003b', 'hex');
    const image = await canvas.loadImage(gif);
    assert.equal(image.width, 1);
    assert.equal(image.height, 1);
    const surface = canvas.createCanvas(1, 1);
    const painter = surface.getContext('2d');
    painter.drawImage(image, 0, 0);
    assert.deepEqual([...painter.getImageData(0, 0, 1, 1).data], [255, 0, 0, 255]);
    console.log('canvas.gif=passed');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
