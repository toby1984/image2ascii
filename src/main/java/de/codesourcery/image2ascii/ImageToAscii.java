/**
 * Copyright 2015 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.codesourcery.image2ascii;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.DataBuffer;
import java.awt.image.LookupOp;
import java.awt.image.ShortLookupTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Converts a grayscale image to ASCII.
 * 
 * The algorithm subdivides each ASCII glyph into 9 quadrants (each having a size of 3x3 pixels)and calculates the average brightness
 * for each of them. The grayscale image is subdivided into 9x9 pixel blocks and each of them is again subdivided into 9 quadrants (3x3 pixels each)
 * and the brightness is calculated for each of these quadrants. 
 * Then a best-fit algorithm runs that compares the brightness in each quadrant of a glyph against the brightness of each quadrant from an image's 9x9 block.
 * The glyph that closest matches the brightness for each of the image block's quadrants will be choosen. 
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class ImageToAscii
{
    private static final boolean DEBUG_GLYPH_MAPPING = false;

    public static final Algorithm ALGORITHM_1 = new Algorithm() 
    {
        public GlyphInfo getGlyphInfo(int averageBrightness, int[] quadrants,GlyphSet set) {
            return set.getGlyphInfo(averageBrightness,quadrants);
        }
        
        @Override
        public String toString() {
            return "Algorithm #1";
        }
    };
    
    public static final Algorithm ALGORITHM_2 = new Algorithm() 
    {
        public GlyphInfo getGlyphInfo(int averageBrightness, int[] quadrants,GlyphSet set) {
            return set.getGlyphInfoAlt(averageBrightness,quadrants);
        }
        
        @Override
        public String toString() {
            return "Algorithm #2";
        }        
    };  
    
    private Algorithm algorithm = ALGORITHM_1;
    
    public static abstract class Algorithm 
    {
        /**
         * Maps a brightness value to a character.
         * 
         * @param averageBrightness average brightness we're looking for
         * @param quadrants average brightness of each of the 9 quadrants.
         * @return glyph that best matches this brightness
         */
        public abstract GlyphInfo getGlyphInfo(int averageBrightness, int[] quadrants,GlyphSet set);        
    }
    
    /**
     * Font size in point used when rendering
     * glyphs to a background buffer to measure their brightness.
     */
    private static final int FONT_RENDER_SIZE_IN_PT = 8;

    /**
     * Y offset used when rendering
     * glyphs to a background buffer to measure their brightness.
     */    
    private static final int FONT_RENDER_Y_OFFSET = 7;

    /**
     * X offset used when rendering
     * glyphs to a background buffer to measure their brightness.
     */     
    private static final int FONT_RENDER_X_OFFSET = 1;

    private static final int BRIGHTNESS_MAX = 255;
    private static final int BRIGHTNESS_MIN = 0;

    // look-up table used to invert images
    private static final short[] INVERSION_TABLE = new short[256];

    private static final Color[] COLORS = new Color[256];

    // row-by-row mapping from pixel positions to quadrants
    private static final int[] QUADRANT_LIST;

    private int whiteThreshold = 255;
    private int blackThreshold = 0;    

    /***
     * Whether to remove leading blank lines as 
     * well as trailing whitespace from each line.
     */
    private boolean cropASCIIOutput = true;

    private final Object LOCK = new Object();

    // @GuardedBy( LOCK )
    private boolean initialized = false;

    // @GuardedBy( LOCK )
    private GlyphSet glyphSet;

    // @GuardedBy( LOCK )
    private boolean use7BitAscii = false; 

    static 
    {
        // grayscale colors
        for ( int i = 0 ; i < 256 ; i++ ) 
        {
            COLORS[i] = new Color(i,i,i);
        }

        // setup inversion look-up table
        for ( short i = 0 ; i < 256 ; i++ ) {
            INVERSION_TABLE[i] = (short) (255-i);
        }

        // pre-calculate mapping from pixel coordinates to quadrants 
        QUADRANT_LIST = new int[9*9];
        int ptr = 0;
        for ( int ry = 0 ; ry < 9 ; ry++ ) 
        {
            for ( int rx = 0 ; rx < 9 ; rx ++ ) 
            {
                final int quadrant;
                if ( rx < 3 ) 
                {
                    if ( ry < 3 ) 
                    {
                        quadrant = 0;
                    } else if ( ry < 6 ) {
                        quadrant = 3;                        
                    } else {
                        quadrant = 6;
                    }
                }
                else if ( rx < 6 ) 
                {
                    if ( ry < 3 ) 
                    {
                        quadrant = 1;
                    } else if ( ry < 6 ) {
                        quadrant = 4;                        
                    } else {
                        quadrant = 7;
                    }                    
                } else {
                    if ( ry < 3 ) 
                    {
                        quadrant = 2;
                    } else if ( ry < 6 ) {
                        quadrant = 5;                        
                    } else {
                        quadrant = 8;
                    }                                   
                }   
                QUADRANT_LIST[ptr++] = quadrant;
            }
        }
    }

    protected static final class GlyphInfo 
    {
        public final char c;
        public int averageBrightness; 
        public int[] quadrants=new int[9];

        public GlyphInfo(char c)
        {
            this.c = c;
        }

        @Override
        public String toString() {
            return "'"+c+"' {"+averageBrightness+"} => "+Arrays.toString( quadrants );
        }
    }

    protected final class GlyphSet 
    {
        public final Map<Integer,GlyphInfo[]> glyphsByTotalPixelsSet = new HashMap<>();

        // glyph to use when there's no glyph that is dark enough 
        private GlyphInfo darkest;

        // glyph to use when there's no glyph that is bright enough
        private GlyphInfo brightest;

        public void add(GlyphInfo glyphInfo) 
        {
            // sanity checking
            if ( glyphInfo.averageBrightness < 0 || glyphInfo.averageBrightness > 255 ) {
                throw new IllegalArgumentException("Invalid brightness: "+glyphInfo);
            }
            final int sum = glyphInfo.quadrants[0]+glyphInfo.quadrants[1]+glyphInfo.quadrants[2]+glyphInfo.quadrants[3]+
                    glyphInfo.quadrants[4]+glyphInfo.quadrants[5]+glyphInfo.quadrants[6]+glyphInfo.quadrants[7]+glyphInfo.quadrants[8];
            if ( sum > 9*255 ) {
                throw new IllegalArgumentException("Invalid brightness: "+glyphInfo);
            }

            if ( darkest == null || glyphInfo.averageBrightness < darkest.averageBrightness ) {
                darkest = glyphInfo;
            }
            if ( brightest == null || glyphInfo.averageBrightness > brightest.averageBrightness ) {
                brightest = glyphInfo;
            }
            final GlyphInfo[] existing = glyphsByTotalPixelsSet.get( glyphInfo.averageBrightness );
            if ( existing == null ) {
                glyphsByTotalPixelsSet.put( Integer.valueOf( glyphInfo.averageBrightness ) , new GlyphInfo[]{ glyphInfo } );   
            } 
            else  
            {
                final GlyphInfo[] tmp = new GlyphInfo[ existing.length + 1 ];
                System.arraycopy( existing , 0 , tmp , 0 , existing.length );
                tmp[ tmp.length-1 ] = glyphInfo;
                glyphsByTotalPixelsSet.put( Integer.valueOf( glyphInfo.averageBrightness ) , tmp );                   
            }
        }

        /**
         * Maps a brightness value to a character.
         * 
         * @param averageBrightness average brightness we're looking for
         * @param quadrants average brightness of each of the 9 quadrants.
         * @return glyph that best matches this brightness
         */
        public char getChar(int averageBrightness, int[] quadrants) 
        {
            return algorithm.getGlyphInfo(averageBrightness, quadrants,this).c;
        }

        /**
         * Maps a brightness value to a character.
         * 
         * @param averageBrightness average brightness we're looking for
         * @param quadrants average brightness of each of the 9 quadrants.
         * @return glyph that best matches this brightness
         */
        public GlyphInfo getGlyphInfo(int averageBrightness, int[] quadrants) 
        {
            final GlyphInfo[] list = glyphsByTotalPixelsSet.get( averageBrightness );
            if ( list != null ) 
            {
                return findBest( quadrants , list );
            }

            int dx = 1;
            while ( averageBrightness-dx > 0 && averageBrightness+dx < 256 ) 
            {
                final GlyphInfo[] list1 = glyphsByTotalPixelsSet.get( averageBrightness+dx );
                final GlyphInfo[] list2 = glyphsByTotalPixelsSet.get( averageBrightness-dx );
                final GlyphInfo g1 = list1 == null ? null : findBest( quadrants , list1 );
                final GlyphInfo g2 = list2 == null ? null : findBest( quadrants , list2 );
                if ( g1 != null && g2 != null ) {
                    return findBest(g1 , g2 , quadrants );
                }
                if ( g1 != null ) {
                    return g1;
                }
                if ( g2 != null ) {
                    return g2;
                }
                dx++;
            }
            if ( averageBrightness-dx == 0 ) {
                return darkest;
            }
            return brightest;
        }
        
        public GlyphInfo getGlyphInfoAlt(int averageBrightness, int[] quadrants) 
        {
            GlyphInfo best = null;
            int dx = 0;
            for (  ; averageBrightness-dx > 0 && averageBrightness+dx < 256  ; dx++ ) 
            {
                final GlyphInfo[] list1 = glyphsByTotalPixelsSet.get( averageBrightness+dx );
                if ( list1 == null ) {
                    continue;
                }
                if ( best == null ) {
                    best = findBest( quadrants , list1 );
                    continue;
                }
                final GlyphInfo[] candidates = new GlyphInfo[ list1.length+1 ];
                System.arraycopy( list1 , 0 , candidates , 0 , list1.length );
                candidates[ candidates.length-1 ] = best;
                
                best = findBest( quadrants , candidates ); 
            }
            if ( best == null ) 
            {
                if ( averageBrightness-dx <= 0 ) {
                    best = darkest;
                } else {
                    best = brightest;
                }
            }
            return best;
        }        

        private GlyphInfo findBest(int[] quadrants,GlyphInfo[] list) 
        {
            GlyphInfo result = null;
            for ( int i = 0 , len = list.length ; i < len ; i++ ) 
            {
                final GlyphInfo glyph = list[i];
                if ( result == null ) {
                    result = glyph;
                } else {
                    result = findBest( result , glyph , quadrants );
                }
            }
            return result;
        }

        private GlyphInfo findBest(GlyphInfo g1,GlyphInfo g2,int[] quadrants) 
        {
            int score1=0;
            int score2=0;
            for ( int i = 0 ; i < 9 ; i++ ) {
                int d1 = Math.abs( g1.quadrants[i] - quadrants[i] );
                int d2 = Math.abs( g2.quadrants[i] - quadrants[i] );
                if ( d1 < d2 ) {
                    score1++;
                } else {
                    score2++;
                }
            }
            return score1 > score2 ? g1 : g2;
        }

        public GlyphInfo getGlyphInfo(char charAt) throws NoSuchElementException
        {
            for ( GlyphInfo[] list : glyphsByTotalPixelsSet.values() ) {
                for ( GlyphInfo info : list ) {
                    if ( info.c == charAt ) {
                        return info;
                    }
                }
            }
            throw new NoSuchElementException("Found no glyph for '"+charAt+" ("+(int) charAt);
        }
    }
    
    public BufferedImage fromASCII9x9(String input) 
    {
        init();

        final String[] lines = input.split("\n");

        // find widest line to calculate img width
        final int widestLine = Arrays.stream( lines ).mapToInt( String::length ).max().getAsInt();
        // pad all lines to max width
        for ( int i =0 ; i < lines.length ; i++ ) 
        {
            int delta = widestLine-lines[i].length();
            String pad = "";
            while ( delta > 0 ) 
            {
                pad+= " ";
                delta--;
            }
            lines[i] = lines[i]+pad;
        }

        final int widthInPixels = widestLine*9;
        final int heightInPixels = lines.length * 9;

        final BufferedImage image = new BufferedImage( widthInPixels , heightInPixels , BufferedImage.TYPE_BYTE_GRAY );
        final Graphics2D gfx = (Graphics2D) image.createGraphics();
        try 
        {
            for ( int i = 0 ; i < lines.length ; i++ ) 
            {
                final String line = lines[i];
                final int y = i*9;
                for ( int j = 0 ; j < widestLine ; j++ ) 
                {
                    final int x = j*9;
                    final GlyphInfo info = glyphSet.getGlyphInfo( line.charAt( j ) );
                    gfx.setColor( COLORS[ info.averageBrightness ]);
                    gfx.fillRect( x , y , 9 , 9 );
                }
            }
        } finally {
            gfx.dispose();
            image.flush();
        }
        return image;
    }    

    public BufferedImage fromASCII(String input) 
    {
        init();

        final String[] lines = input.split("\n");

        // find widest line to calculate img width
        final int widestLine = Arrays.stream( lines ).mapToInt( String::length ).max().getAsInt();
        // pad all lines to max width
        for ( int i =0 ; i < lines.length ; i++ ) 
        {
            int delta = widestLine-lines[i].length();
            String pad = "";
            while ( delta > 0 ) 
            {
                pad+= " ";
                delta--;
            }
            lines[i] = lines[i]+pad;
        }

        final int widthInPixels = widestLine*9;
        final int heightInPixels = lines.length * 9;

        final BufferedImage image = new BufferedImage( widthInPixels , heightInPixels , BufferedImage.TYPE_BYTE_GRAY );
        final Graphics2D gfx = (Graphics2D) image.createGraphics();
        try 
        {
            for ( int i = 0 ; i < lines.length ; i++ ) 
            {
                final String line = lines[i];
                final int y = i*9;
                for ( int j = 0 ; j < widestLine ; j++ ) 
                {
                    final int x = j*9;
                    final GlyphInfo info = glyphSet.getGlyphInfo( line.charAt( j ) );
                    int quadrantPtr = 0;
                    for ( int dy = 0 ; dy < 9 ; dy+= 3 ) 
                    {
                        for ( int dx = 0 ; dx < 9 ; dx+= 3 ) 
                        {
                            final int quadrant = QUADRANT_LIST[ quadrantPtr++ ];
                            gfx.setColor( COLORS[ info.quadrants[ quadrant] ]);
                            gfx.fillRect( x+dx , y+dy , 3 , 3 );
                        }
                    }
                }
            }
        } finally {
            gfx.dispose();
            image.flush();
        }
        return image;
    }

    public BufferedImage average(BufferedImage input) 
    {
        assertImageIsSane( input );

        final DataBuffer buffer = input.getRaster().getDataBuffer();

        final int imageWidth = input.getWidth();
        final int imageHeight = input.getHeight();      

        if ( (imageWidth/9)*9 != imageWidth ) {
            throw new IllegalArgumentException("Image width must be a multiple of 9");
        }

        final BufferedImage result = new BufferedImage( imageWidth , imageHeight , BufferedImage.TYPE_BYTE_GRAY );
        final Graphics2D gfx = (Graphics2D) result.createGraphics();
        try 
        {
            for ( int y = 0 ; y < imageHeight ; y+=9)
            {
                for ( int x = 0 ; x < imageWidth ; x+=9) 
                {
                    int sum = 0;
                    for ( int dy = 0 ; dy < 9 ; dy++ ) 
                    {
                        final int yOffset = (y+dy) * imageWidth;
                        for ( int dx = 0 ; dx < 9 ; dx++ ) 
                        {
                            final int color;
                            if ( (x+dx) >= imageWidth || (y+dy) >= imageHeight ) {
                                color = BRIGHTNESS_MAX;
                            } else {
                                color = buffer.getElem( x + dx + yOffset ) & 0xff;
                            }
                            sum += color;
                        }
                    }
                    
                    gfx.setColor( COLORS[ sum / 81 ] );
                    gfx.fillRect( x , y , 9 , 9 ); 
                }
            }
            return result;
        } 
        finally 
        {
            gfx.dispose();
            result.flush();
        }        
    }
    
    public BufferedImage downsample(BufferedImage input) 
    {
        assertImageIsSane( input );

        final DataBuffer buffer = input.getRaster().getDataBuffer();

        final int imageWidth = input.getWidth();
        final int imageHeight = input.getHeight();      

        if ( (imageWidth/9)*9 != imageWidth ) {
            throw new IllegalArgumentException("Image width must be a multiple of 9");
        }

        final BufferedImage result = new BufferedImage( imageWidth , imageHeight , BufferedImage.TYPE_BYTE_GRAY );
        final Graphics2D gfx = (Graphics2D) result.createGraphics();
        try 
        {
            final int[] quadrants = new int[9];
            for ( int y = 0 ; y < imageHeight ; y+=9)
            {
                for ( int x = 0 ; x < imageWidth ; x+=9) 
                {
                    int quadrantPtr = 0;
                    quadrants[0] = quadrants[1] =quadrants[2] =quadrants[3] =quadrants[4] =quadrants[5] =quadrants[6] =quadrants[7] =quadrants[8] = 0; 
                    for ( int dy = 0 ; dy < 9 ; dy++ ) 
                    {
                        final int yOffset = (y+dy) * imageWidth;
                        for ( int dx = 0 ; dx < 9 ; dx++ ) 
                        {
                            final int color;
                            if ( (x+dx) >= imageWidth || (y+dy) >= imageHeight ) {
                                color = BRIGHTNESS_MAX;
                            } else {
                                color = buffer.getElem( x + dx + yOffset ) & 0xff;
                            }
                            quadrants[ QUADRANT_LIST[ quadrantPtr ] ] += color;
                        }
                    }

                    int brightnessSum = quadrants[0] + quadrants[1] + quadrants[2] + quadrants[3]+
                            quadrants[4] + quadrants[5] + quadrants[6] + quadrants[7]+
                            quadrants[8];                    

                    quadrants[0] /= 9;
                    quadrants[1] /= 9;
                    quadrants[2] /= 9;
                    quadrants[3] /= 9;
                    quadrants[4] /= 9;
                    quadrants[5] /= 9;
                    quadrants[6] /= 9;
                    quadrants[7] /= 9;
                    quadrants[8] /= 9;

                    final GlyphInfo glyph = algorithm.getGlyphInfo( brightnessSum / 81 , quadrants , glyphSet );
                    gfx.setColor( COLORS[ glyph.averageBrightness] );
                    gfx.fillRect( x , y , 9 , 9 ); 
                }
            }
            return result;
        } 
        finally 
        {
            gfx.dispose();
            result.flush();
        }        
    }

    public static void assertImageIsSane(BufferedImage image)
    {
        final int width = image.getWidth();
        if ( (width/9)*9 != width ) {
            throw new IllegalArgumentException("Image width must be a multiple of 9");
        }

        final DataBuffer buffer = image.getRaster().getDataBuffer();
        if ( buffer.getNumBanks() != 1 || buffer.getDataType() != DataBuffer.TYPE_BYTE) {
            throw new IllegalArgumentException("Expected image to have a byte buffer with exactly 1 bank");
        }
    }

    public String toASCII(BufferedImage toConvert) 
    {
        init();

        assertImageIsSane( toConvert );

        final int imageHeight = toConvert.getHeight();        
        final int imageWidth = toConvert.getWidth();

        final DataBuffer buffer = toConvert.getRaster().getDataBuffer();

        final List<String> lines = new ArrayList<>();
        final StringBuilder lineBuffer = new StringBuilder();        
        final int[] quadrants = new int[9];
        for ( int iy = 0 ; iy < imageHeight ; iy+= 9 ) 
        {
            for ( int ix = 0 ; ix < imageWidth ; ix+= 9 ) 
            {
                quadrants[0] = quadrants[1] = quadrants[2] = quadrants[3] = quadrants[4] = quadrants[5] = quadrants[6] = quadrants[7] = quadrants[8] = 0;
                int quadrantPtr = 0;
                for ( int y = iy , ymax = iy + 9 ; y < ymax ; y++ ) 
                {
                    int yOffset = y * imageWidth;
                    for ( int x = ix , xmax = ix + 9 ; x < xmax ; x++ ) 
                    {
                        int brightness;
                        if ( x >= imageWidth || y >= imageHeight ) {
                            brightness = 255;
                        } else {
                            brightness = buffer.getElem( x + yOffset );
                            if ( brightness >= whiteThreshold ) {
                                brightness = BRIGHTNESS_MAX;
                            } else if ( brightness <= blackThreshold ) {
                                brightness = BRIGHTNESS_MIN;
                            }
                        }
                        quadrants[ QUADRANT_LIST[quadrantPtr++] ]+=brightness;
                    }
                }
                int brightnessSum = quadrants[0] + quadrants[1] + quadrants[2] + quadrants[3]+
                        quadrants[4] + quadrants[5] + quadrants[6] + quadrants[7]+
                        quadrants[8];
                quadrants[0] /= 9; // 3x3 pixels per quadrant
                quadrants[1] /= 9;
                quadrants[2] /= 9;
                quadrants[3] /= 9;
                quadrants[4] /= 9;
                quadrants[5] /= 9;
                quadrants[6] /= 9;
                quadrants[7] /= 9;
                quadrants[8] /= 9;

                lineBuffer.append( glyphSet.getChar( brightnessSum / 81 , quadrants ) ); // 81 = 9x9 pixels
            }

            // trim trailing spaces
            if ( cropASCIIOutput ) 
            {
                int end = lineBuffer.length();
                while ( end-1 >= 0 && lineBuffer.charAt( end-1 ) == ' ' ) {
                    end--;
                }
                if ( end != lineBuffer.length() ) {
                    lineBuffer.delete( end , lineBuffer.length() );
                }
            }
            lines.add( lineBuffer.toString() );
            lineBuffer.setLength( 0 );
        }

        final StringBuilder outputBuffer = new StringBuilder();
        boolean foundOnlyBlankLines = true;
        for (Iterator<String> it = lines.iterator(); it.hasNext();) 
        {
            final String line = it.next();
            if ( cropASCIIOutput && foundOnlyBlankLines && isBlank( line ) ) 
            {
                continue;
            }
            foundOnlyBlankLines = false;
            if ( outputBuffer.length() > 0 ) {
                outputBuffer.append("\n");
            }
            outputBuffer.append( line );
        }
        return outputBuffer.toString();
    }

    private static boolean isBlank(String line) 
    {
        if ( line.length() == 0 ) {
            return true;
        }
        for ( int i = 0 , len = line.length() ; i < len ; i++ ) {
            if ( ! Character.isWhitespace( line.charAt( i ) ) ) {
                return false;
            }
        }
        return true;
    }

    private void init() 
    {
        synchronized(LOCK) 
        {
            if ( ! initialized ) 
            {
                final GlyphSet set = new GlyphSet();
                final BufferedImage image = new BufferedImage(32, 32 , BufferedImage.TYPE_BYTE_GRAY);
                final Graphics2D g = (Graphics2D) image.createGraphics();

                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING , RenderingHints.VALUE_ANTIALIAS_OFF);
                g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION , RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING , RenderingHints.VALUE_TEXT_ANTIALIAS_OFF );

                try 
                {
                    g.setFont( new Font( Font.MONOSPACED , Font.PLAIN , FONT_RENDER_SIZE_IN_PT ) );

                    final int maxAscii = use7BitAscii ? 127 : 255;
                    for ( int ascii = 32 ; ascii < maxAscii ; ascii++ ) 
                    {
                        if ( ascii == 127 || ascii == 160 ) { // ignore DEL and SHIFT-SPACE
                            continue;
                        }
                        final GlyphInfo glyphInfo = new GlyphInfo( (char) ascii );
                        g.setColor( Color.BLACK );
                        g.fillRect( 0 , 0 , 32 ,32 );
                        g.setColor( Color.WHITE );
                        g.drawString( Character.toString( (char) ascii ) , FONT_RENDER_X_OFFSET , FONT_RENDER_Y_OFFSET );

                        int quadrantPtr = 0;
                        for ( int y = 0 ; y < 9 ; y++ ) 
                        {                        
                            for ( int x = 0 ; x < 9 ; x++ ) 
                            {                            
                                final int color = image.getRGB( x , y ) & 0xffffff;
                                if ( color == 0 ) // set pixel are black, unset pixel are white
                                {
                                    glyphInfo.quadrants[ QUADRANT_LIST[quadrantPtr] ]+=BRIGHTNESS_MAX;                                    
                                    if (DEBUG_GLYPH_MAPPING) {
                                        System.out.print(".");
                                    }
                                } else {
                                    if ( DEBUG_GLYPH_MAPPING ) {
                                        System.out.print("x");
                                    }
                                }
                                quadrantPtr++;
                            }
                            if ( DEBUG_GLYPH_MAPPING ) {
                                System.out.println();
                            }
                        }
                        // calculate average brightness per quadrant
                        final int brightnessSum = glyphInfo.quadrants[0]+glyphInfo.quadrants[1]+glyphInfo.quadrants[2]+glyphInfo.quadrants[3]+
                                glyphInfo.quadrants[4]+glyphInfo.quadrants[5]+glyphInfo.quadrants[6]+glyphInfo.quadrants[7]+
                                glyphInfo.quadrants[8];
                        glyphInfo.quadrants[0] /= 9; // 3x3 pixels per quadrant
                        glyphInfo.quadrants[1] /= 9;
                        glyphInfo.quadrants[2] /= 9;
                        glyphInfo.quadrants[3] /= 9;
                        glyphInfo.quadrants[4] /= 9;
                        glyphInfo.quadrants[5] /= 9;
                        glyphInfo.quadrants[6] /= 9;
                        glyphInfo.quadrants[7] /= 9;
                        glyphInfo.quadrants[8] /= 9;
                        glyphInfo.averageBrightness = brightnessSum / 81; // 81 = 9x9
                        if ( DEBUG_GLYPH_MAPPING ) {
                            System.out.println("ASCII "+(int) glyphInfo.c+" => "+glyphInfo);
                        }
                        set.add( glyphInfo );                        
                    }
                } finally {
                    g.dispose();
                }
                glyphSet = set;
                initialized = true;
            }
        }
    }

    public static BufferedImage scaleImage(BufferedImage input,int newWidth,int newHeight) 
    {
        final BufferedImage result = new BufferedImage(newWidth, newHeight, input.getType() );
        try {
            final Graphics2D g2 = result.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING , RenderingHints.VALUE_ANTIALIAS_OFF);
                g2.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION , RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.drawImage(input, 0, 0, newWidth , newHeight , null);
            }
            finally {
                g2.dispose();
            }
            return result;
        }
        finally {
            result.flush();
        }
    }

    public static BufferedImage toGrayscale(BufferedImage input) 
    {
        final BufferedImage image = new BufferedImage(input.getWidth(), input.getHeight() , BufferedImage.TYPE_BYTE_GRAY);  
        Graphics gfx = image.getGraphics();  
        gfx.drawImage( input , 0, 0, null);  
        gfx.dispose(); 
        return image;
    }

    public static BufferedImage invertGrayscaleImage(final BufferedImage src) 
    {
        final BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        final BufferedImageOp invertOp = new LookupOp(new ShortLookupTable(0, INVERSION_TABLE), null);
        return invertOp.filter(src, dst);
    }    

    public void setWhiteThreshold(int whiteThreshold) {
        this.whiteThreshold = whiteThreshold;
    }

    public void setBlackThreshold(int blackThreshold) {
        this.blackThreshold = blackThreshold;
    }

    public boolean isUse7BitAscii() {
        return use7BitAscii;
    }

    public void setUse7BitAscii(boolean use7BitAscii) 
    {
        synchronized ( LOCK ) 
        {        
            if ( this.use7BitAscii != use7BitAscii ) 
            {
                initialized = false;
            }
            this.use7BitAscii = use7BitAscii;
        }
    }

    public void setCropASCIIOutput(boolean cropASCIIOutput) {
        this.cropASCIIOutput = cropASCIIOutput;
    }

    public boolean isCropASCIIOutput() {
        return cropASCIIOutput;
    }
    
    public static BufferedImage delta(BufferedImage image1,BufferedImage image2) 
    {
        if ( image1.getWidth() != image2.getWidth() ) {
            throw new IllegalArgumentException("Width mismatch: "+image1.getWidth()+" <-> "+image2.getWidth());
        }
        final int maxHeight = Math.min( image1.getHeight() , image2.getHeight() );
        
        final BufferedImage result = new BufferedImage( image1.getWidth() , maxHeight , BufferedImage.TYPE_BYTE_GRAY );
        try 
        {
            for ( int x = 0 , w = image1.getWidth() ; x < w ; x++ ) 
            {
                for ( int y = 0 , h = maxHeight ; y < h ; y++ ) 
                {
                    final int col1 = image1.getRGB(x, y) & 0xff;
                    final int col2 = image2.getRGB(x, y) & 0xff;
                    final int delta = Math.abs( col1 - col2 );
                    final int color = 0xff000000 | delta << 16 | delta << 8 | delta;
                    result.setRGB( x , y , color ); 
                }
            }
            float sum = 0;
            float blocks = 0;
            for ( int y = 0 , h = maxHeight ; y < h ; y+=9 ) 
            {
                for ( int x = 0 , w = image1.getWidth() ; x < w ; x+=9 ) 
                {
                    sum += result.getRGB(x, y) & 0xff;
                    blocks++;
                }
            }            
            
            final Graphics2D gfx = result.createGraphics();
            try 
            {
                final float avgDelta = sum / blocks;
                gfx.setColor( Color.RED );
                gfx.setFont( new Font( Font.MONOSPACED , Font.BOLD , 20 ) );
                gfx.drawString("Avg. delta: "+avgDelta,25,25);
                System.out.println("Avg. delta: "+avgDelta);
                return result;
            } finally {
                gfx.dispose();
            }
        } 
        finally 
        {
            result.flush();
        }        
    }
    
    public void setAlgorithm(Algorithm algorithm) {
        this.algorithm = algorithm;
    }
    
    public Algorithm getAlgorithm() {
        return algorithm;
    }
}