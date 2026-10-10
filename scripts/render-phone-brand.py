#!/usr/bin/env python3
"""Render the shared flip-phone SVG with system librsvg/Cairo; no browser or image crops.
Requires Python 3 and the shared libraries librsvg-2, cairo and gobject-2.0.
"""
from pathlib import Path
import ctypes as C
from ctypes.util import find_library
import sys
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'app/src/wehavephones/res'
MASTER = ROOT / 'docs/design/brand/flip-phone.svg'
MARK = MASTER.read_text()
S = '{http://www.w3.org/2000/svg}'
A = '{http://schemas.android.com/apk/res/android}'
E.register_namespace('android', A[1:-1])

def library(name):
    path = find_library(name)
    if not path: raise SystemExit(f'Missing {name}; install the system librsvg/Cairo libraries')
    return C.CDLL(path)

cairo, rsvg, obj = library('cairo'), library('rsvg-2'), library('gobject-2.0')
def sig(lib, name, result, *args):
    fn = getattr(lib, name); fn.restype = result; fn.argtypes = args; return fn
ptr, integer, double = C.c_void_p, C.c_int, C.c_double
new = sig(rsvg, 'rsvg_handle_new_from_data', ptr, C.c_char_p, C.c_size_t, ptr)
render = sig(rsvg, 'rsvg_handle_render_cairo', integer, ptr, ptr)
surface = sig(cairo, 'cairo_image_surface_create', ptr, integer, integer, integer)
context = sig(cairo, 'cairo_create', ptr, ptr)
scale = sig(cairo, 'cairo_scale', None, ptr, double, double)
png = sig(cairo, 'cairo_surface_write_to_png', integer, ptr, C.c_char_p)
free = sig(obj, 'g_object_unref', None, ptr)
free_context = sig(cairo, 'cairo_destroy', None, ptr)
free_surface = sig(cairo, 'cairo_surface_destroy', None, ptr)

def document(content, w, h):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">{content}</svg>'

def mark(x, y, size, monochrome=False):
    content = MARK[MARK.index('  <g'):MARK.rindex('</svg>')]
    if monochrome:
        t=E.fromstring(document(content,256,256))
        dark={'#0D315A','#153B62','#5C8F50'}
        for p in t.iter(S+'path'):
            p.set('fill','#FFFFFF' if p.get('fill') in dark else '#000000')
            if 'stroke' in p.attrib:p.set('stroke','#FFFFFF')
        mask=''.join(E.tostring(p,encoding='unicode') for p in t)
        content='<defs><mask id="phone"><rect width="256" height="256" fill="black"/>'+mask+'</mask></defs><rect width="256" height="256" fill="black" mask="url(#phone)"/>'
    return f'<g transform="translate({x} {y}) scale({size/256})">{content}</g>'

def output(svg, path, w=None, h=None):
    t=E.fromstring(svg); sw=float(t.get('width')); sh=float(t.get('height'))
    w,h=int(w or sw),int(h or sh)
    data=svg.encode(); handle=new(data,len(data),None)
    if not handle:raise RuntimeError('Invalid SVG')
    img=surface(0,w,h); ctx=context(img)
    try:
        scale(ctx,w/sw,h/sh)
        if not render(handle,ctx): raise RuntimeError('SVG render failed')
        path.parent.mkdir(parents=True,exist_ok=True)
        if png(img,str(path).encode()):raise RuntimeError(f'PNG write failed: {path}')
    finally: free_context(ctx);free_surface(img);free(handle)

def native_vector():
    src=E.fromstring(MARK)
    v=E.Element('vector',{A+'width':'64dp',A+'height':'64dp',A+'viewportWidth':'256',A+'viewportHeight':'256'})
    group=E.SubElement(v,'group',{A+'rotation':'-14',A+'pivotX':'128',A+'pivotY':'128'})
    for p in src.find(S+'g'):
        if p.tag!=S+'path':continue
        attrs={A+'pathData':p.get('d'),A+'fillColor':p.get('fill')}
        for key,name in [('stroke','strokeColor'),('stroke-width','strokeWidth'),('stroke-linejoin','strokeLineJoin')]:
            if key in p.attrib: attrs[A+name]=p.get(key)
        E.SubElement(group,'path',attrs)
    E.indent(v,space='    ')
    (RES/'drawable/wehavephones_flip_phone.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n'+E.tostring(v,encoding='unicode')+'\n')

def icons():
    native_vector()
    for density,size in [('mdpi',108),('hdpi',162),('xhdpi',216),('xxhdpi',324),('xxxhdpi',432)]:
        folder=RES/f'mipmap-{density}'
        output(document(mark(23,19,62),108,108),folder/'ic_launcher_foreground.png',size,size)
        output(document('<rect width="108" height="108" fill="#287FD0"/>',108,108),folder/'ic_launcher_background.png',size,size)
        output(document(mark(23,19,62,True),108,108),folder/'ic_launcher_monochrome.png',size,size)
        content='<rect width="72" height="72" rx="13" fill="#287FD0"/>'+mark(8,3,58)
        legacy=size*48//108
        output(document(content,72,72),folder/'ic_launcher.png',legacy,legacy)
        output(document('<defs><clipPath id="round"><circle cx="36" cy="36" r="36"/></clipPath></defs><g clip-path="url(#round)">'+content+'</g>',72,72),folder/'ic_launcher_round.png',legacy,legacy)
    for density,size in [('hdpi',64),('xxhdpi',128)]:
        output(document(mark(0,0,256,True),256,256),RES/f'drawable-{density}/icon_container.png',size,size)
    output(document(mark(28,4,200),256,256),RES/'drawable-hdpi/wallpaper.png',512,512)
    output(document('<rect width="512" height="512" rx="94" fill="#287FD0"/>'+mark(48,15,416),512,512),ROOT/'docs/design/app-icon/preview.png')
    print('Rendered native vector, adaptive/legacy icons and Wine wallpaper')

def wordmark(x,y,size,color='#FFFFFF'):
    return f'<text x="{x}" y="{y}" font-family="DejaVu Sans" font-weight="bold" font-size="{size}" fill="{color}">We<tspan fill="#62B2F2">Have</tspan>Phones</text>'

def brand():
    folder=ROOT/'docs/brand'
    # The social preview enlarges the banner layout about the centre of its
    # drawn area (x 85-1038, y 191-440) so it fills the taller canvas.
    for name,w,h,zoom in [('banner',1280,400,1),('social-preview',1280,640,1.25)]:
        y=(h-280)/2
        content=mark(65,y,265)+wordmark(380,h/2,76)
        content+=f'<text x="384" y="{h/2+48}" fill="#BCD0A6" font-family="DejaVu Sans" font-size="22">World of Warcraft on Android and Wine.</text>'
        content+=f'<text x="384" y="{h/2+84}" fill="#A9BDCD" font-family="DejaVu Sans" font-size="17">Unofficial; not affiliated with Blizzard.</text>'
        if zoom!=1:content=f'<g transform="translate({w/2} {h/2}) scale({zoom}) translate(-561.5 {-h/2+4})">{content}</g>'
        output(document(f'<rect width="{w}" height="{h}" fill="#0B2344"/>'+content,w,h),folder/f'{name}.png')
    output(document(mark(18,4,276)+wordmark(330,181,95,'#0D315A'),1200,300),folder/'logo.png')
    output(document('<rect width="512" height="512" rx="94" fill="#287FD0"/>'+mark(48,15,416),512,512),folder/'icon.png')
    print('Rendered README banner, social preview and transparent wordmark')

if __name__=='__main__':
    mode=sys.argv[1] if len(sys.argv)>1 else 'all'
    if mode in ['icons','all']:icons()
    if mode in ['brand','all']:brand()
    if mode not in ['icons','brand','all']:raise SystemExit('Use icons, brand or all')
