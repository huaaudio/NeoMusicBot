// Exercise the installed native addon and PNG encoder, without network or fonts.
const { createCanvas } = require(process.argv[2]);
const canvas = createCanvas(2, 2);
const context = canvas.getContext('2d');
context.fillStyle = '#12abef';
context.fillRect(0, 0, 2, 2);
if (String(context.getImageData(0, 0, 1, 1).data) !== '18,171,239,255') {
    throw new Error('Canvas native pixel rendering failed');
}
if (canvas.toBuffer('image/png').subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') {
    throw new Error('Canvas native PNG encoding failed');
}
console.log('canvas.native=passed');
