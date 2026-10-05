package com.vulkanis.shadow;
public final class ShadowManager {
  public static final float DEFAULT_BIAS = 0.0005f;
  public static final float NORMAL_OFFSET = 0.02f;
  private ShadowManager(){}
  public static float[] computeMatrix(float[] sunDir, float[] cam, float half, float near, float far) {
    float len = (float)Math.sqrt(sunDir[0]*sunDir[0]+sunDir[1]*sunDir[1]+sunDir[2]*sunDir[2]);
    if (len < 1e-6f) throw new IllegalArgumentException("zero sun dir");
    float[] f = {sunDir[0]/len, sunDir[1]/len, sunDir[2]/len};
    float[] eye = {cam[0]-f[0]*100f, cam[1]-f[1]*100f, cam[2]-f[2]*100f};
    float[] up = Math.abs(f[1]) > 0.99f ? new float[]{0,0,-1} : new float[]{0,1,0};
    float[] z = {-f[0],-f[1],-f[2]};
    float[] x = norm(cross(up, z)); float[] y = cross(z, x);
    float[] view = lookAt(eye, cam, x, y, z);
    float[] proj = ortho(-half, half, -half, half, near, far);
    return mul(proj, view);
  }
  static float[] norm(float[] v){float l=(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);return new float[]{v[0]/l,v[1]/l,v[2]/l};}
  static float[] cross(float[] a,float[] b){return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
  static float[] lookAt(float[] e,float[] c,float[] x,float[] y,float[] z){
    return new float[]{x[0],y[0],z[0],0, x[1],y[1],z[1],0, x[2],y[2],z[2],0,
      -(x[0]*e[0]+x[1]*e[1]+x[2]*e[2]), -(y[0]*e[0]+y[1]*e[1]+y[2]*e[2]), -(z[0]*e[0]+z[1]*e[1]+z[2]*e[2]), 1};
  }
  static float[] ortho(float l,float r,float b,float t,float n,float f){
    return new float[]{2/(r-l),0,0,0, 0,2/(t-b),0,0, 0,0,-2/(f-n),0, -(r+l)/(r-l),-(t+b)/(t-b),-(f+n)/(f-n),1};
  }
  static float[] mul(float[] a,float[] b){
    float[] o=new float[16];
    for(int c=0;c<4;c++)for(int r=0;r<4;r++){o[c*4+r]=a[r]*b[c*4]+a[4+r]*b[c*4+1]+a[8+r]*b[c*4+2]+a[12+r]*b[c*4+3];}
    return o;
  }
}
