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

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import de.codesourcery.image2ascii.ImageToAscii.Algorithm;

public class Main
{
    private File lastFile = new File("/home/tobi/tmp/test.jpg");
    
    private final JSlider whiteThreshold = new JSlider(JSlider.HORIZONTAL , 0,255,255);
    private final JSlider blackThreshold = new JSlider(JSlider.HORIZONTAL , 0,255,0);
    private final JSlider maxImgWidth = new JSlider(JSlider.HORIZONTAL , 0,100,100);
    
    private final ImageToAscii converter = new ImageToAscii();
    
    private final JTextArea textArea = new JTextArea();    
    private final JCheckBox invert = new JCheckBox("Invert");
    private final JCheckBox cropASCII = new JCheckBox("Crop ASCII");
    private final JCheckBox use7BitAscii = new JCheckBox("7-bit ASCII");
    
    public static enum DeltaImageType {
        AVERAGE,
        DELTA,
        FROM_ASCII;
    }
    
    private final JComboBox<DeltaImageType>  showDownsampledImage = new JComboBox<DeltaImageType>( DeltaImageType.values() );
    
    private final JComboBox<ImageToAscii.Algorithm> algorithm = new JComboBox<ImageToAscii.Algorithm>( new ImageToAscii.Algorithm[]{ ImageToAscii.ALGORITHM_1,ImageToAscii.ALGORITHM_2});
    
    private final JFrame frame = new JFrame();    
    
    private BufferedImage inputImage;
    private BufferedImage outputImage;
    
    private File lastSaved;
    
    private final JPanel inputImagePanel = new JPanel() 
    {
        @Override
        protected void paintComponent(Graphics g) 
        {
            super.paintComponent(g);
            if ( inputImage != null ) {
                g.drawImage( inputImage , 0 , 0 , null );
            }
        }
    };
    
    private final JPanel outputImagePanel = new JPanel() 
    {
        @Override
        protected void paintComponent(Graphics g) 
        {
            super.paintComponent(g);
            if ( outputImage != null ) {
                g.drawImage( outputImage , 0 , 0 , null );
            }
        }
    };    
    
    public Main() 
    {
        converter.setCropASCIIOutput( false );
    }

    public static void main(String[] args) throws IOException, InvocationTargetException, InterruptedException
    {
        SwingUtilities.invokeAndWait( () -> 
        {
            try {
                new Main().run();
            } catch (IOException e) 
            {
                e.printStackTrace();
            }
        });
    }

    public void run() throws IOException 
    {
        textArea.setFont( new Font( Font.MONOSPACED , Font.PLAIN , 8 ) );

        frame.setMinimumSize( new Dimension(640,480));
        frame.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );
        frame.getContentPane().setLayout( new GridBagLayout() );

        invert.setHorizontalTextPosition( SwingConstants.LEFT );
        use7BitAscii.setHorizontalTextPosition( SwingConstants.LEFT );
        
        whiteThreshold.setMinimumSize( new Dimension(80,25 ) );
        blackThreshold.setMinimumSize( new Dimension(80,25 ) );
        maxImgWidth.setMinimumSize( new Dimension(80,25 ) );     
        
        final JButton load = new JButton("Load");
        final JButton reload = new JButton("Reload");
        final JButton save = new JButton("Save");
        
        final JToolBar buttonPanel = new JToolBar(JToolBar.HORIZONTAL);
        final JPanel miscPanel = new JPanel();
        miscPanel.setLayout( new GridBagLayout() );
        
        buttonPanel.add( load );
        buttonPanel.add( reload );
        buttonPanel.add( save );
        
        miscPanel.add( invert , cnstrs(0,0).build() );
        miscPanel.add( cropASCII , cnstrs(1,0).build() );
        miscPanel.add( showDownsampledImage , cnstrs(2,0).build() );
        miscPanel.add( algorithm , cnstrs(3,0).build() );
        miscPanel.add( use7BitAscii , cnstrs(4,0).build() );
        miscPanel.add( new JLabel("Image width:") , cnstrs(5,0).build() );
        miscPanel.add( maxImgWidth , cnstrs(6,0).build());
        
        reload.addActionListener( ev -> {
            loadImage( lastFile );
            render();
        });        
        save.addActionListener( ev -> {
            save();
        });        
        algorithm.addActionListener( ev -> {
            render();
        });
        cropASCII.addActionListener( ev -> {
            render();
        });
        showDownsampledImage.addActionListener( ev -> {
            render();
        });
        invert.addActionListener( ev -> 
        {
            loadImage( lastFile );
            render();
        });        
        use7BitAscii.addActionListener( ev -> {
            render();
        });
        whiteThreshold.addChangeListener( ev -> this.render() );
        blackThreshold.addChangeListener( ev -> this.render() );        
        maxImgWidth.addChangeListener( ev -> 
        {
            loadImage( lastFile );
            render();
        });        

        // do layout
        final JPanel sliderPanel = new JPanel();
        sliderPanel.setLayout( new GridBagLayout() );
        
        sliderPanel.add( new JLabel("Black threshold:" )  , cnstrs(0,0).build() );
        sliderPanel.add( blackThreshold, cnstrs(1,0).fillHorizontal().weightX(0.5).build() );
        sliderPanel.add( new JLabel("White threshold:" )  , cnstrs(2,0).build() );
        sliderPanel.add( whiteThreshold, cnstrs(3,0).fillHorizontal().weightX(0.5).build() );
        
        frame.getContentPane().add( buttonPanel , cnstrs( 0, 0 ).build() );
        frame.getContentPane().add( miscPanel   , cnstrs( 1, 0 ).build() );
        frame.getContentPane().add( sliderPanel , cnstrs( 2, 0 ).fillHorizontal().weightX(0.5).build() );
        
        inputImagePanel.setSize( 640, 240 );
        outputImagePanel.setSize( 640, 240 );

        final JPanel imagePanel = new JPanel();
        imagePanel.setLayout( new GridBagLayout() );
        
        imagePanel.add( inputImagePanel  , cnstrs(0,0).weightX(1).weightY(0.5).fillBoth().build() );
        imagePanel.add( outputImagePanel , cnstrs(0,1).weightX(1).weightY(0.5).fillBoth().build() );
        
        final JSplitPane verticalSplitPane = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT, new JScrollPane( textArea ), new JScrollPane( imagePanel ) );
        
        verticalSplitPane.setResizeWeight( 0.5 );
        frame.getContentPane().add( verticalSplitPane , cnstrs(0,1).width(3).fillBoth().weightX(1.0).weightY(1.0).build() );
        
        frame.setSize( new Dimension(640,480 ) );
        frame.pack();
        load.addActionListener( ev -> 
        {
            final JFileChooser chooser = lastFile == null ? new JFileChooser() : new JFileChooser( lastFile.getParentFile() );
            if ( lastFile != null ) {
                chooser.setSelectedFile( lastFile );
            }

            if ( chooser.showOpenDialog( frame ) == JFileChooser.APPROVE_OPTION ) 
            {
                loadImage( chooser.getSelectedFile() );
                render();
            }
        });
        loadImage( lastFile );
        render();        
        frame.setVisible( true );
    }

    private void loadImage(File file) 
    {
        if ( file == null ) {
            return;
        }
        try 
        {
            BufferedImage image = ImageIO.read( file );
            final int percentage = maxImgWidth.getValue();
            if ( percentage != 100 || (image.getWidth()/9)*9 != image.getWidth()  ) 
            {
                int newWidth = image.getWidth();
                if ( percentage != 100 ) {
                    newWidth = (int) (newWidth * (percentage/100f));
                }
                newWidth = (newWidth / 9 ) * 9;
                final float scale = newWidth / (float) image.getWidth();
                int newHeight = (int) (image.getHeight()*scale);
                System.out.println("Downscaling image to "+newWidth+"x"+newHeight);
                image = ImageToAscii.scaleImage( image, newWidth , newHeight );
            }            
            if ( image.getColorModel().getNumColorComponents() != 1 ) {
                image = ImageToAscii.toGrayscale( image );
            }
            if ( invert.isSelected() ) {
                image = ImageToAscii.invertGrayscaleImage( image );
            }
            ImageToAscii.assertImageIsSane( image );
            lastFile = file;
            frame.setTitle( lastFile.getAbsolutePath()+" : "+image.getWidth()+"x"+image.getHeight());
            inputImage = image;
        } 
        catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void save() 
    {
        final String ascii = getASCII(cropASCII.isSelected());
        final JFileChooser chooser = lastSaved == null ? new JFileChooser() : new JFileChooser( lastSaved.getPath() );
        if ( lastSaved != null ) {
            chooser.setSelectedFile( lastSaved); 
        }
        if ( chooser.showSaveDialog( frame ) == JFileChooser.APPROVE_OPTION ) 
        {
            BufferedWriter writer = null;
            try
            {
                writer = new BufferedWriter( new FileWriter( chooser.getSelectedFile() ) );
                try {
                    writer.write( ascii );
                } finally {
                    writer.close();
                }
                lastSaved = chooser.getSelectedFile(); 
            } 
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    private String getASCII(boolean cropASCII) {
        if ( inputImage == null ) {
            return "";
        }
        converter.setWhiteThreshold( whiteThreshold.getValue() );
        converter.setBlackThreshold( blackThreshold.getValue() );
        converter.setUse7BitAscii( use7BitAscii.isSelected() );
        converter.setAlgorithm( (Algorithm) algorithm.getSelectedItem() );
        converter.setCropASCIIOutput( cropASCII );
        
        final long time = System.currentTimeMillis();
        final String text = converter.toASCII( inputImage );
        final long elapsed = System.currentTimeMillis() - time;
        System.out.println("Time: "+elapsed+" ms");
        return text;
    }

    public void render() {
        if ( inputImage == null ) {
            return;
        }
        final String text = getASCII( cropASCII.isSelected() );
        switch ( getDeltaImageType() ) 
        {
            case DELTA:
                final BufferedImage image1 = converter.average( inputImage );
                final String text2 = ! cropASCII.isSelected() ? text : getASCII( false );
                final BufferedImage image2 = converter.fromASCII9x9( text2 );
                System.out.println("Image #1: "+image1.getWidth()+"x"+image1.getHeight());
                System.out.println("Image #2: "+image2.getWidth()+"x"+image2.getHeight());
                outputImage = ImageToAscii.delta(image1,image2);
                break;
            case AVERAGE:
                outputImage = converter.average( inputImage );
                break;
            case FROM_ASCII:
                outputImage = converter.fromASCII9x9( text );
                break;
            default:
                throw new RuntimeException("Unreachable code reached");
        }
        textArea.setText( text );
        showInputImage( inputImage );
        showOutputImage( outputImage );
        frame.revalidate();
        System.out.println("Repaint");
    }

    private DeltaImageType getDeltaImageType() {
        return (DeltaImageType) showDownsampledImage.getSelectedItem();
    }
    
    private static CnstrBuilder cnstrs(int x,int y) {
        return new CnstrBuilder(x,y);
    }
    
    private static final class CnstrBuilder {
        
        final GridBagConstraints cnstrs = new GridBagConstraints();
        
        public CnstrBuilder(int x,int y) 
        {
            cnstrs.gridx = x ; cnstrs.gridy = y;
            cnstrs.gridheight = cnstrs.gridwidth = 1;
            cnstrs.fill = GridBagConstraints.NONE;
            cnstrs.weightx = cnstrs.weighty = 0;
        }
        
        public GridBagConstraints build() {
            return cnstrs;
        }
        
        public CnstrBuilder width(int w) 
        {
            cnstrs.gridwidth = w;
            return this;
        }        
        
        public CnstrBuilder weightX(double w) 
        {
            cnstrs.weightx = w;
            return this;
        }          
        
        public CnstrBuilder weightY(double w) 
        {
            cnstrs.weighty = w;
            return this;
        }         
        
        public CnstrBuilder fillHorizontal() 
        {
            cnstrs.fill = GridBagConstraints.HORIZONTAL;
            return this;
        }
        
        public CnstrBuilder fillBoth() 
        {
            cnstrs.fill = GridBagConstraints.BOTH;
            return this;
        }        
    }
    
    private void showOutputImage(BufferedImage image) 
    {
        this.outputImage = image;
        outputImagePanel.setPreferredSize( new Dimension(image.getWidth(),image.getHeight() ) );
        outputImagePanel.invalidate();
        outputImagePanel.repaint();
    }    

    private void showInputImage(BufferedImage image) 
    {
        this.inputImage = image;
        inputImagePanel.setPreferredSize( new Dimension(image.getWidth(),image.getHeight() ) );
        inputImagePanel.invalidate();
        inputImagePanel.repaint();
    }
}