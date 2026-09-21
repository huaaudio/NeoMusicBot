/*
 * Copyright 2026 JMusicBot contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.jagrosh.jmusicbot.audio;

/**
 * Opaque per-guild reservation for one asynchronous user media load.
 * A token is useful only while its originating playback session retains it.
 */
public record PlaybackLoadToken(long epoch, long sequence)
{
}
