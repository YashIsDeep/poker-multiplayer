// T8: WebGL Perlin-fBm swirl background (shader from the approved mockups).
// Graceful degradation: if WebGL or shader compilation is unavailable, the
// canvas renders nothing and the CSS gradient behind it stays visible.
// prefers-reduced-motion renders exactly one frame (no RAF loop).

import { useEffect, useRef } from 'react'

const VERTEX_SHADER = 'attribute vec2 a;void main(){gl_Position=vec4(a,0.,1.);}'

// highp is required, not cosmetic: the value-noise hash below is
// fract(sin(dot(p,·))*43758.5453). Under mediump the ~64-wide quantization
// step at that magnitude collapses the hash to a handful of values, so the fbm
// degenerates into coarse bands that the radial rotation wraps into concentric
// rings — the "dead eye". Desktop GPUs mask this by running mediump as highp;
// phones/Intel/ARM honor mediump and break. See highp guard in the effect.
const FRAGMENT_SHADER = `
precision highp float;
uniform float t; uniform vec2 r;
float h(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float n(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);
  return mix(mix(h(i),h(i+vec2(1,0)),f.x),mix(h(i+vec2(0,1)),h(i+vec2(1,1)),f.x),f.y);}
float fbm(vec2 p){float v=0.,a=.5;for(int i=0;i<5;i++){v+=a*n(p);p*=2.03;a*=.5;}return v;}
void main(){
  vec2 uv=(gl_FragCoord.xy-.5*r)/min(r.x,r.y);
  float T=t*.045;
  float ang=T+2.2*length(uv);
  mat2 rot=mat2(cos(ang),-sin(ang),sin(ang),cos(ang));
  vec2 p=rot*uv*2.1;
  vec2 q=vec2(fbm(p+T*.7),fbm(p+vec2(5.2,1.3)-T*.5));
  float f=fbm(p+2.6*q);
  vec3 c1=vec3(.169,.294,.435);
  vec3 c2=vec3(.078,.145,.220);
  vec3 col=mix(c2,c1,smoothstep(.15,.95,f));
  col+=.05*f*f*vec3(.9,1.,1.2);
  float d=length(uv);
  col*=1.-.35*d*d;
  gl_FragColor=vec4(col,1.);
}`

export default function SwirlBackground() {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    let gl: WebGLRenderingContext | null = null
    try {
      gl = canvas.getContext('webgl', { antialias: false, depth: false })
    } catch {
      gl = null
    }
    if (!gl) return // CSS gradient fallback stays visible

    // The fragment shader needs highp (see FRAGMENT_SHADER note). If the device
    // can't offer it, don't draw the mediump-mangled version — leave the CSS
    // gradient, which looks intentional rather than broken.
    const highp = gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT)
    if (!highp || highp.precision < 1) return

    const compile = (type: number, source: string): WebGLShader | null => {
      const shader = gl.createShader(type)
      if (!shader) return null
      gl.shaderSource(shader, source)
      gl.compileShader(shader)
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) return null
      return shader
    }

    const vs = compile(gl.VERTEX_SHADER, VERTEX_SHADER)
    const fs = compile(gl.FRAGMENT_SHADER, FRAGMENT_SHADER)
    if (!vs || !fs) return // silent fallback

    const program = gl.createProgram()
    if (!program) return
    gl.attachShader(program, vs)
    gl.attachShader(program, fs)
    gl.linkProgram(program)
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return
    gl.useProgram(program)

    const buffer = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW)
    const loc = gl.getAttribLocation(program, 'a')
    gl.enableVertexAttribArray(loc)
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0)
    const uT = gl.getUniformLocation(program, 't')
    const uR = gl.getUniformLocation(program, 'r')

    const resize = () => {
      // Render at native density (DPR capped at 2 for very-hi-DPI fill-rate sanity).
      // Previously undersampled at 0.66×/1.25 DPR, which read as blocky on retina.
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      canvas.width = Math.max(1, Math.floor(canvas.clientWidth * dpr))
      canvas.height = Math.max(1, Math.floor(canvas.clientHeight * dpr))
      gl.viewport(0, 0, canvas.width, canvas.height)
    }
    resize()
    window.addEventListener('resize', resize)

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const t0 = performance.now()
    let raf = 0
    let disposed = false

    const frame = () => {
      if (disposed) return
      if (!document.hidden) {
        gl.uniform1f(uT, (performance.now() - t0) / 1000)
        gl.uniform2f(uR, canvas.width, canvas.height)
        gl.drawArrays(gl.TRIANGLES, 0, 3)
      }
      if (!reduced) raf = requestAnimationFrame(frame)
    }
    frame() // reduced-motion renders exactly one frame

    return () => {
      disposed = true
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', resize)
    }
  }, [])

  return <canvas id="swirl" ref={canvasRef} aria-hidden="true" />
}
