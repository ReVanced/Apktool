/*
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.androlib.res.decoder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import brut.androlib.exceptions.AndrolibException;
import brut.androlib.exceptions.NinePatchNotFoundException;
import brut.androlib.res.data.LayoutBounds;
import brut.androlib.res.data.NinePatchData;
import brut.util.BinaryDataInputStream;
import org.apache.commons.io.IOUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.*;
import java.nio.ByteOrder;


public class ResNinePatchStreamDecoder implements ResStreamDecoder {
    private final NinePatchImageBackend imageBackend;

    public ResNinePatchStreamDecoder(NinePatchImageBackend imageBackend) {
        this.imageBackend = imageBackend;
    }

    @Override
    public void decode(InputStream in, OutputStream out) throws AndrolibException {
        try {
            byte[] data = IOUtils.toByteArray(in);
            if (data.length == 0) {
                return;
            }

            NinePatchImage src = imageBackend.read(data);

            int w = src.getWidth();
            int h = src.getHeight();

            NinePatchImage dst = imageBackend.createArgb(w + 2, h + 2);
            imageBackend.draw(dst, src, 1, 1);

            NinePatchData np = findNinePatchData(data);

            drawHLine(dst, h + 1, np.paddingLeft + 1, w - np.paddingRight);
            drawVLine(dst, w + 1, np.paddingTop + 1, h - np.paddingBottom);

            int[] xDivs = np.xDivs;
            if (xDivs.length == 0) {
                drawHLine(dst, 0, 1, w);
            } else {
                for (int i = 0; i < xDivs.length; i += 2) {
                    drawHLine(dst, 0, xDivs[i] + 1, xDivs[i + 1]);
                }
            }

            int[] yDivs = np.yDivs;
            if (yDivs.length == 0) {
                drawVLine(dst, 0, 1, h);
            } else {
                for (int i = 0; i < yDivs.length; i += 2) {
                    drawVLine(dst, 0, yDivs[i] + 1, yDivs[i + 1]);
                }
            }

            try {
                LayoutBounds lb = findLayoutBounds(data);

                for (int i = 0; i < lb.left; i++) {
                    dst.setArgb(1 + i, h + 1, LayoutBounds.COLOR_TICK);
                }

                for (int i = 0; i < lb.right; i++) {
                    dst.setArgb(w - i, h + 1, LayoutBounds.COLOR_TICK);
                }

                for (int i = 0; i < lb.top; i++) {
                    dst.setArgb(w + 1, 1 + i, LayoutBounds.COLOR_TICK);
                }

                for (int i = 0; i < lb.bottom; i++) {
                    dst.setArgb(w + 1, h - i, LayoutBounds.COLOR_TICK);
                }
            } catch (NinePatchNotFoundException ignored) {
                // This chunk might not exist.
            }

            imageBackend.writePng(dst, out);
        } catch (IOException | NullPointerException ex) {
            throw new AndrolibException(ex);
        }
    }

    private NinePatchData findNinePatchData(byte[] data) throws NinePatchNotFoundException, IOException {
        BinaryDataInputStream in = new BinaryDataInputStream(data, ByteOrder.BIG_ENDIAN);
        findChunk(in, NinePatchData.MAGIC);
        return NinePatchData.read(in);
    }

    private LayoutBounds findLayoutBounds(byte[] data) throws NinePatchNotFoundException, IOException {
        BinaryDataInputStream in = new BinaryDataInputStream(data, ByteOrder.BIG_ENDIAN);
        findChunk(in, LayoutBounds.MAGIC);
        return LayoutBounds.read(in);
    }

    private void findChunk(BinaryDataInputStream in, int magic) throws NinePatchNotFoundException, IOException {
        in.skipBytes(8);

        for (; ; ) {
            int size;
            try {
                size = in.readInt();
            } catch (EOFException ignored) {
                throw new NinePatchNotFoundException();
            }

            if (in.readInt() == magic) {
                return;
            }

            in.skipBytes(size + 4);
        }
    }

    private void drawHLine(NinePatchImage image, int y, int x1, int x2) {
        for (int x = x1; x <= x2; x++) {
            image.setArgb(x, y, NinePatchData.COLOR_TICK);
        }
    }

    private void drawVLine(NinePatchImage image, int x, int y1, int y2) {
        for (int y = y1; y <= y2; y++) {
            image.setArgb(x, y, NinePatchData.COLOR_TICK);
        }
    }


    public interface NinePatchImageBackend {
        NinePatchImage read(byte[] data) throws IOException;

        NinePatchImage createArgb(int width, int height);

        void draw(NinePatchImage dst, NinePatchImage src, int dstX, int dstY);

        void writePng(NinePatchImage image, OutputStream out) throws IOException;
    }

    interface NinePatchImage {
        int getWidth();

        int getHeight();

        void setArgb(int x, int y, int argb);
    }

    public static final class AwtNinePatchImageBackend implements NinePatchImageBackend {
        @Override
        public NinePatchImage read(byte[] data) throws IOException {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                throw new IOException("Invalid image");
            }

            return new AwtNinePatchImage(image);
        }

        @Override
        public NinePatchImage createArgb(int width, int height) {
            return new AwtNinePatchImage(
                new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            );
        }

        @Override
        public void draw(NinePatchImage dst, NinePatchImage src, int dstX, int dstY) {
            BufferedImage srcImage = unwrap(src);
            BufferedImage dstImage = unwrap(dst);

            int w = srcImage.getWidth();
            int h = srcImage.getHeight();

            if (srcImage.getType() == BufferedImage.TYPE_CUSTOM) {
                Raster srcRaster = srcImage.getRaster();
                WritableRaster dstRaster = dstImage.getRaster();

                int[] gray = null;
                int[] alpha = null;

                for (int y = 0; y < h; y++) {
                    gray = srcRaster.getSamples(0, y, w, 1, 0, gray);
                    alpha = srcRaster.getSamples(0, y, w, 1, 1, alpha);

                    dstRaster.setSamples(dstX, dstY + y, w, 1, 0, gray);
                    dstRaster.setSamples(dstX, dstY + y, w, 1, 1, gray);
                    dstRaster.setSamples(dstX, dstY + y, w, 1, 2, gray);
                    dstRaster.setSamples(dstX, dstY + y, w, 1, 3, alpha);
                }

                return;
            }

            Graphics2D graphics = dstImage.createGraphics();
            try {
                graphics.drawImage(srcImage, dstX, dstY, w, h, null);
            } finally {
                graphics.dispose();
            }
        }

        @Override
        public void writePng(NinePatchImage image, OutputStream out) throws IOException {
            ImageIO.write(unwrap(image), "png", out);
        }

        private static BufferedImage unwrap(NinePatchImage image) {
            return ((AwtNinePatchImage) image).image;
        }

        private static final class AwtNinePatchImage implements NinePatchImage {
            private final BufferedImage image;

            private AwtNinePatchImage(BufferedImage image) {
                this.image = image;
            }

            @Override
            public int getWidth() {
                return image.getWidth();
            }

            @Override
            public int getHeight() {
                return image.getHeight();
            }

            @Override
            public void setArgb(int x, int y, int argb) {
                image.setRGB(x, y, argb);
            }
        }
    }


    public static final class AndroidNinePatchImageBackend implements NinePatchImageBackend {
        @Override
        public NinePatchImage read(byte[] data) throws IOException {
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null) {
                throw new IOException("Invalid image");
            }

            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }

            return new AndroidNinePatchImage(bitmap);
        }

        @Override
        public NinePatchImage createArgb(int width, int height) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            return new AndroidNinePatchImage(bitmap);
        }

        @Override
        public void draw(NinePatchImage dst, NinePatchImage src, int dstX, int dstY) {
            Bitmap dstBitmap = unwrap(dst);
            Bitmap srcBitmap = unwrap(src);

            android.graphics.Canvas canvas = new android.graphics.Canvas(dstBitmap);
            canvas.drawBitmap(srcBitmap, dstX, dstY, null);
        }

        @Override
        public void writePng(NinePatchImage image, OutputStream out) throws IOException {
            boolean success = unwrap(image).compress(Bitmap.CompressFormat.PNG, 100, out);
            if (!success) {
                throw new IOException("Failed to encode PNG");
            }
        }

        private static Bitmap unwrap(NinePatchImage image) {
            return ((AndroidNinePatchImage) image).bitmap;
        }

        private static final class AndroidNinePatchImage implements NinePatchImage {
            private final Bitmap bitmap;

            private AndroidNinePatchImage(Bitmap bitmap) {
                this.bitmap = bitmap;
            }

            @Override
            public int getWidth() {
                return bitmap.getWidth();
            }

            @Override
            public int getHeight() {
                return bitmap.getHeight();
            }

            @Override
            public void setArgb(int x, int y, int argb) {
                bitmap.setPixel(x, y, argb);
            }
        }
    }
}
