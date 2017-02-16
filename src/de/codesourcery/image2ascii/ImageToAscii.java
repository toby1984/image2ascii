package de.codesourcery.image2ascii;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ImageToAscii
{
    public final int BOX_WIDTH  = 8;
    public final int BOX_HEIGHT = 8;

    private int threshold = 0x70;
    private boolean invert=true;
    
    public static final class GlyphInfo 
    {
        public final char c;
        public int totalPixelsSet; 
        public int[] quadrants=new int[4];

        public GlyphInfo(char c)
        {
            this.c = c;
        }
        
        @Override
        public String toString() {
            return "'"+c+"' {"+totalPixelsSet+"} => "+Arrays.toString( quadrants );
        }
    }

    public static final class GlyphSet 
    {
        public final Map<Integer,List<GlyphInfo>> glyphsByTotalPixelsSet = new HashMap<>();
        
        public void add(GlyphInfo glyphInfo) 
        {
            List<GlyphInfo> existing = glyphsByTotalPixelsSet.get( glyphInfo.totalPixelsSet );
            if ( existing == null ) {
                existing = new ArrayList<>();
                glyphsByTotalPixelsSet.put( Integer.valueOf( glyphInfo.totalPixelsSet ) , existing );
            }            
            existing.add( glyphInfo );
        }
        
        public char getChar(int[] quadrants) 
        {
            final int totalPixels = quadrants[0] + quadrants[1] + quadrants[2] + quadrants[3];
            final List<GlyphInfo> list = glyphsByTotalPixelsSet.get( totalPixels );
            if ( list != null ) 
            {
                return findBest( quadrants , list ).c;
            }

            int dx = 1;
            while ( totalPixels-dx > 0 ) 
            {
                final List<GlyphInfo> list1 = glyphsByTotalPixelsSet.get( totalPixels+dx );
                final List<GlyphInfo> list2 = glyphsByTotalPixelsSet.get( totalPixels-dx );
                final GlyphInfo g1 = list1 == null ? null : findBest( quadrants , list1 );
                final GlyphInfo g2 = list2 == null ? null : findBest( quadrants , list2 );
                if ( g1 != null && g2 != null ) {
                    return findBest(g1 , g2 , quadrants ).c;
                }
                if ( g1 != null ) {
                    return g1.c;
                }
                if ( g2 != null ) {
                    return g2.c;
                }
                dx++;
            }
            return '?';
        }
        
        private GlyphInfo findBest(int[] quadrants,List<GlyphInfo> list) 
        {
            GlyphInfo result = null;
            for ( int i = 0 , len = list.size() ; i < len ; i++ ) 
            {
                final GlyphInfo glyph = list.get(i);
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
            for ( int i = 0 ; i < 4 ; i++ ) {
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
    }
    
    private final Object LOCK = new Object();
    
    // @GuardedBy( LOCK )
    private boolean initialized = false;

    // @GuardedBy( LOCK )
    private GlyphSet glyphSet;
    
    public String convert(BufferedImage image) 
    {
        System.out.println("Input image: "+image.getWidth()+"x"+image.getHeight());
        BufferedImage toConvert = image;
        if ( image.getSampleModel().getNumBands() != 1 ) 
        {
            toConvert = toGrayscale( toConvert );
        }

        init();
        
        final int imageWidth = image.getWidth();
        final int imageHeight = image.getHeight();

        final int outputWidth = (int) Math.ceil( imageWidth / 8f );
        final int outputHeight = (int) Math.ceil( imageHeight / 8f );

        final char[][] outputBuffer = new char[ outputWidth ][];
        for ( int i = 0 ; i < outputWidth ; i++ ) {
            outputBuffer[i] = new char[ outputHeight ];
        }
        final int[] quadrants = new int[4];
        for ( int iy = 0 ; iy < imageHeight ; iy+= 8 ) 
        {
            for ( int ix = 0 ; ix < imageWidth ; ix+= 8 ) 
            {
                quadrants[0] = quadrants[1] = quadrants[2] = quadrants[3] = 0;
                for ( int y = iy , ymax = Math.min( imageHeight , iy + 8 ) ; y < ymax ; y++ ) 
                {
                    for ( int x = ix , xmax = Math.min( imageWidth , ix + 8 ) ; x < xmax ; x++ ) 
                    {
                        final int color = toConvert.getRGB(x, y) & 0xff;
                        if ( ( ! invert && color > threshold ) || (invert && color <= threshold ) ) 
                        {
                            final int quadrant;
                            if ( x <= 3 ) {
                                if ( y <= 3 ) {
                                    quadrant = 0;
                                } else {
                                    quadrant = 2;
                                }
                            } else {
                                if ( y <= 3 ) {
                                    quadrant = 1;
                                } else {
                                    quadrant = 3;
                                }                                    
                            }
                            quadrants[quadrant]++;
                        }
                    }
                }
                outputBuffer[ix/8][iy/8] = glyphSet.getChar( quadrants );
            }
        }
        final StringBuilder result = new StringBuilder();
        for ( int y = 0 ; y < outputHeight ; y++ ) 
        {
            for ( int x = 0 ; x < outputWidth ; x++ ) {
                result.append( outputBuffer[x][y] );
            }
            if ( (y+1) < outputHeight ) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    private void init() 
    {
        synchronized(LOCK) 
        {
            if ( ! initialized ) 
            {
                final GlyphSet set = new GlyphSet();
                final BufferedImage image = new BufferedImage(32, 32 , BufferedImage.TYPE_BYTE_GRAY);
                final Graphics g = image.createGraphics();
                try 
                {
                    g.setFont( new Font( Font.MONOSPACED , Font.PLAIN , 7 ) );
                    for ( int ascii = 32 ; ascii < 127 ; ascii++ ) 
                    {
                        final GlyphInfo glyphInfo = new GlyphInfo( (char) ascii );
                        g.setColor( Color.BLACK );
                        g.fillRect( 0 , 0 , 32 ,32 );
                        g.setColor( Color.WHITE );
                        g.drawString( Character.toString( (char) ascii ) , 2 , 6 );

                        for ( int y = 0 ; y < 8 ; y++ ) 
                        {                        
                            for ( int x = 0 ; x < 8 ; x++ ) 
                            {                            
                                final int color = image.getRGB( x , y ) & 0xffffff;
                                if ( color != 0 ) 
                                {
                                    final int quadrant;
                                    if ( x <= 3 ) {
                                        if ( y <= 3 ) {
                                            quadrant = 0;
                                        } else {
                                            quadrant = 2;
                                        }
                                    } else {
                                        if ( y <= 3 ) {
                                            quadrant = 1;
                                        } else {
                                            quadrant = 3;
                                        }                                    
                                    }
                                    glyphInfo.quadrants[quadrant]++;
                                    // System.out.print("X");
                                } else {
                                    // System.out.print(".");
                                }
                            }
                            // System.out.println();
                        }
                        glyphInfo.totalPixelsSet = glyphInfo.quadrants[0]+glyphInfo.quadrants[1]+glyphInfo.quadrants[2]+glyphInfo.quadrants[3];
                        // System.out.println( glyphInfo );
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

    private BufferedImage toGrayscale(BufferedImage input) 
    {
        final BufferedImage image = new BufferedImage(input.getWidth(), input.getHeight() , BufferedImage.TYPE_BYTE_GRAY);  
        Graphics gfx = image.getGraphics();  
        gfx.drawImage( input , 0, 0, null);        
        gfx.dispose(); 
        
//        final JFrame frame = new JFrame();
//        frame.add( new JPanel() 
//        {
//            @Override
//            protected void paintComponent(Graphics g) 
//            {
//                g.drawImage( image , 0 , 0 , null );
//            }
//        });
//        frame.setSize( 640, 480 );
//        frame.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );
//        frame.setVisible( true );
        return image;
    }
    
    public int getThreshold() {
        return threshold;
    }
    
    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }
    
    public void setInvert(boolean invert) {
        this.invert = invert;
    }

    public boolean getInvert() {
        return invert;
    }
}