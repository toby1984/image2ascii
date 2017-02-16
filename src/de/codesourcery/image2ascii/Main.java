package de.codesourcery.image2ascii;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

public class Main
{
    private File lastFile;
    
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
    
    private final JTextArea textArea = new JTextArea();    
    private final JCheckBox invert = new JCheckBox("Invert");
    
    private final JSlider slider = new JSlider( JSlider.HORIZONTAL , 0 , 255 , 0x50 );
    public void run() throws IOException 
    {
        textArea.setFont( new Font( Font.MONOSPACED , Font.PLAIN , 8 ) );
        
        slider.setSize( new Dimension(250,30 ));
        final JFrame frame = new JFrame();
         frame.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );
        frame.getContentPane().setLayout( new GridBagLayout() );

        slider.getModel().addChangeListener( new ChangeListener() {
            
            @Override
            public void stateChanged(ChangeEvent e) 
            {
                
                render( lastFile );
            }
        });

        invert.addActionListener( ev -> 
        {
            render( lastFile );
        });
        final JButton load = new JButton("Load...");
        frame.getContentPane().add( load , cnstrs(0,0 ) );
        frame.getContentPane().add( invert, cnstrs(1,0 ) );
        
        GridBagConstraints cnstrs2 = cnstrs(2,0 );
        cnstrs2.fill = GridBagConstraints.HORIZONTAL;
        frame.getContentPane().add( slider , cnstrs2 );
        
        final GridBagConstraints cnstrs = cnstrs(0,1);
        cnstrs.weightx = cnstrs.weighty = 1.0;
        cnstrs.gridwidth = 3;
        cnstrs.fill = GridBagConstraints.BOTH;
        frame.getContentPane().add( new JScrollPane( textArea ) , cnstrs );
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
                render( chooser.getSelectedFile() );
            }
        });
        frame.setVisible( true );
    }
    
    public void render(File file) {
        if ( file == null ) {
            return;
        }
        final ImageToAscii conv = new ImageToAscii();
        conv.setThreshold( slider.getValue() );
        conv.setInvert( invert.isSelected() );
        
        try {
            final BufferedImage image = ImageIO.read( file );
            lastFile = file;
            textArea.setText( conv.convert( image ) );
        } 
        catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private GridBagConstraints cnstrs(int x,int y) 
    {
        final GridBagConstraints cnstrs = new GridBagConstraints();
        cnstrs.gridx = x ; cnstrs.gridy = y;
        cnstrs.gridheight = cnstrs.gridwidth = 1;
        cnstrs.fill = GridBagConstraints.NONE;
        cnstrs.weightx = cnstrs.weighty = 0;
        return cnstrs;
    }
}