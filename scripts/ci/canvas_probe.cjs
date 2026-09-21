// Exercise the shipped native formats without network, fonts, or external fixtures.
const assert = require('node:assert/strict');
const { createCanvas, loadImage } = require(process.argv[2]);

(async () => {
    const canvas = createCanvas(16, 16);
    const context = canvas.getContext('2d');
    context.fillStyle = '#12abef';
    context.fillRect(0, 0, 16, 16);
    assert.deepEqual([...context.getImageData(8, 8, 1, 1).data], [18, 171, 239, 255]);

    const png = canvas.toBuffer('image/png');
    assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
    context.clearRect(0, 0, 16, 16);
    context.drawImage(await loadImage(png), 0, 0);
    assert.deepEqual([...context.getImageData(8, 8, 1, 1).data], [18, 171, 239, 255]);

    const jpeg = canvas.toBuffer('image/jpeg', { quality: 1 });
    assert.equal(jpeg.subarray(0, 2).toString('hex'), 'ffd8');
    context.clearRect(0, 0, 16, 16);
    context.drawImage(await loadImage(jpeg), 0, 0);
    const decoded = [...context.getImageData(8, 8, 1, 1).data];
    for (const [index, expected] of [18, 171, 239].entries()) {
        assert(Math.abs(decoded[index] - expected) <= 3, 'JPEG decoded color differs');
    }

    const svg = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16">'
        + '<rect x="4" y="4" width="8" height="8" fill="#cc3366"/></svg>');
    context.clearRect(0, 0, 16, 16);
    context.drawImage(await loadImage(svg), 0, 0);
    assert.deepEqual([...context.getImageData(8, 8, 1, 1).data], [204, 51, 102, 255]);
    assert.equal(context.getImageData(0, 0, 1, 1).data[3], 0);

    for (const format of ['pdf', 'svg']) {
        const vector = createCanvas(16, 16, format);
        vector.getContext('2d').fillRect(2, 2, 8, 8);
        const body = vector.toBuffer();
        if (format === 'pdf') assert.equal(body.subarray(0, 4).toString(), '%PDF');
        else assert(body.toString().includes('<svg'));
    }
    console.log('canvas.native=passed');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
