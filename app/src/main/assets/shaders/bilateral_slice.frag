#version 300 es
precision highp float;
precision highp sampler3D;

in vec2 vTexCoord;
out vec4 FragColor;

uniform sampler2D uInputTexture;
uniform sampler3D uGridTexture0;
uniform sampler3D uGridTexture1;
uniform sampler3D uGridTexture2;

void main() {
    vec4 color = texture(uInputTexture, vTexCoord);
    float luma = clamp(dot(color.rgb, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
    vec3 sliceCoord = vec3(vTexCoord.x, vTexCoord.y, luma);
    
    vec4 affR = texture(uGridTexture0, sliceCoord);
    vec4 affG = texture(uGridTexture1, sliceCoord);
    vec4 affB = texture(uGridTexture2, sliceCoord);
    
    vec3 enhanced;
    enhanced.r = dot(affR.rgb, color.rgb) + affR.a;
    enhanced.g = dot(affG.rgb, color.rgb) + affG.a;
    enhanced.b = dot(affB.rgb, color.rgb) + affB.a;
    
    FragColor = vec4(clamp(enhanced, 0.0, 1.0), color.a);
}
