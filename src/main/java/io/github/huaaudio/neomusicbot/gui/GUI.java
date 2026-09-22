/* Modified by Huaaudio for NeoMusicBot (2026). */
/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.huaaudio.neomusicbot.gui;

import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.WindowConstants;
import io.github.huaaudio.neomusicbot.Bot;


/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class GUI extends JFrame 
{
    private final ConsolePanel console;
    private final Bot bot;
    
    /** Create and show Swing components on their event dispatch thread. */
    public static void open(Bot bot) throws InvocationTargetException, InterruptedException
    {
        Runnable show = () ->
        {
            GUI gui = new GUI(bot);
            bot.setGUI(gui);
            gui.init();
        };
        if(EventQueue.isDispatchThread())
            show.run();
        else
            EventQueue.invokeAndWait(show);
    }

    public GUI(Bot bot) 
    {
        super();
        this.bot = bot;
        console = new ConsolePanel();
    }
    
    public void init()
    {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setTitle("NeoMusicBot");
        JTabbedPane tabs = new JTabbedPane();
        tabs.add("Console", console);
        getContentPane().add(tabs);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
        addWindowListener(new WindowListener() 
        {
            @Override public void windowOpened(WindowEvent e) { /* unused */ }
            @Override public void windowClosing(WindowEvent e) 
            {
                // Keep Swing responsive while flushing settings and closing audio.
                // shutdown() disposes the window after the services have closed.
                new Thread(bot::shutdown, "NeoMusicBot-GUI-shutdown").start();
            }
            @Override public void windowClosed(WindowEvent e) { /* unused */ }
            @Override public void windowIconified(WindowEvent e) { /* unused */ }
            @Override public void windowDeiconified(WindowEvent e) { /* unused */ }
            @Override public void windowActivated(WindowEvent e) { /* unused */ }
            @Override public void windowDeactivated(WindowEvent e) { /* unused */ }
        });
    }
}
