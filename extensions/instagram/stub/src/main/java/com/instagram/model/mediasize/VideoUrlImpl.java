/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package com.instagram.model.mediasize;

/**
 * Compile-only stub of IG's VideoUrlImpl (runtime class supplied by the patched app).
 * Field layout verified on 439.0.0.37.89 via the 08ik.A00 ctor call sites:
 * A00 = height, A01 = type, A02 = width, A06 = url.
 */
public class VideoUrlImpl {
    public int A00;
    public int A01;
    public int A02;
    public VideoUrlImpl A03;
    public Long A04;
    public String A05;
    public String A06;
}
