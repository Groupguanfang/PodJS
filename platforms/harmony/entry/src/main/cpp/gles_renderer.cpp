#include "gles_renderer.h"
#include <GLES3/gl3.h>
bool GlesRenderer::validate(const uint32_t* w,size_t n)const{size_t i=0;while(i<n){size_t z=0;switch(w[i]){case 1:z=4;break;case 2:z=6;break;case 3:if(i+3>n)return false;z=3+2*(w[i+1]>>16);break;case 4:z=9;break;case 5:z=3;break;case 6:z=1;break;case 7:z=7;break;case 8:z=12;break;case 9:if(i+8>n)return false;z=8+(w[i+7]+3)/4;break;case 10:z=9;break;default:return false;}if(z>n-i)return false;i+=z;}return true;}
bool GlesRenderer::submit(const uint32_t*w,size_t n,uint64_t h){if(h==hash_)return false;if(!validate(w,n))return false;glDisable(GL_DEPTH_TEST);hash_=h;return glGetError()==GL_NO_ERROR;}
