/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include "rsContext.h"
#include "rsProgramVertex.h"

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;
using namespace android::renderscript;


ProgramVertex::ProgramVertex(Context *rsc, bool texMat) :
    Program(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mTextureMatrixEnable = texMat;
    mLightCount = 0;
    init(rsc);
}

ProgramVertex::ProgramVertex(Context *rsc, const char * shaderText,
                             uint32_t shaderLength, const uint32_t * params,
                             uint32_t paramLength) :
    Program(rsc, shaderText, shaderLength, params, paramLength)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mTextureMatrixEnable = false;
    mLightCount = 0;

    init(rsc);
}

ProgramVertex::~ProgramVertex()
{
}

static void logMatrix(const char *txt, const float *f)
{
    LOGV("Matrix %s, %p", txt, f);
    LOGV("%6.4f, %6.4f, %6.4f, %6.4f", f[0], f[4], f[8], f[12]);
    LOGV("%6.4f, %6.4f, %6.4f, %6.4f", f[1], f[5], f[9], f[13]);
    LOGV("%6.4f, %6.4f, %6.4f, %6.4f", f[2], f[6], f[10], f[14]);
    LOGV("%6.4f, %6.4f, %6.4f, %6.4f", f[3], f[7], f[11], f[15]);
}

void ProgramVertex::setupGL(const Context *rsc, ProgramVertexState *state)
{
    if ((state->mLast.get() == this) && !mDirty) {
        return;
    }
    state->mLast.set(this);

    const float *f = static_cast<const float *>(mConstants[0]->getPtr());

    glMatrixMode(GL_TEXTURE);
    if (mTextureMatrixEnable) {
        glLoadMatrixf(&f[RS_PROGRAM_VERTEX_TEXTURE_OFFSET]);
    } else {
        glLoadIdentity();
    }

    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
    if (mLightCount) {
        int v = 0;
        glEnable(GL_LIGHTING);
        glLightModelxv(GL_LIGHT_MODEL_TWO_SIDE, &v);
        for (uint32_t ct = 0; ct < mLightCount; ct++) {
            const Light *l = mLights[ct].get();
            glEnable(GL_LIGHT0 + ct);
            l->setupGL(ct);
        }
        for (uint32_t ct = mLightCount; ct < MAX_LIGHTS; ct++) {
            glDisable(GL_LIGHT0 + ct);
        }
    } else {
        glDisable(GL_LIGHTING);
    }

    if (!f) {
        LOGE("Must bind constants to vertex program");
    }

    glMatrixMode(GL_PROJECTION);
    glLoadMatrixf(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
    glMatrixMode(GL_MODELVIEW);
    glLoadMatrixf(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET]);

    mDirty = false;
}

void ProgramVertex::loadShader(Context *rsc) {
    Program::loadShader(rsc, GL_VERTEX_SHADER);
}

void ProgramVertex::createShader()
{
    mShader.setTo("");

    mShader.append("varying vec4 varColor;\n");
    mShader.append("varying vec4 varTex0;\n");

    if (mUserShader.length() > 1) {
        mShader.append("uniform mat4 ");
        mShader.append(mUniformNames[0]);
        mShader.append(";\n");

        for (uint32_t ct=0; ct < mConstantCount; ct++) {
            const Element *e = mConstantTypes[ct]->getElement();
            for (uint32_t field=0; field < e->getFieldCount(); field++) {
                const Element *f = e->getField(field);

                // Cannot be complex
                rsAssert(!f->getFieldCount());
                switch(f->getComponent().getVectorSize()) {
                case 1: mShader.append("uniform float UNI_"); break;
                case 2: mShader.append("uniform vec2 UNI_"); break;
                case 3: mShader.append("uniform vec3 UNI_"); break;
                case 4: mShader.append("uniform vec4 UNI_"); break;
                default:
                    rsAssert(0);
                }

                mShader.append(e->getFieldName(field));
                mShader.append(";\n");
            }
        }


        for (uint32_t ct=0; ct < mInputCount; ct++) {
            const Element *e = mInputElements[ct].get();
            for (uint32_t field=0; field < e->getFieldCount(); field++) {
                const Element *f = e->getField(field);

                // Cannot be complex
                rsAssert(!f->getFieldCount());
                switch(f->getComponent().getVectorSize()) {
                case 1: mShader.append("attribute float ATTRIB_"); break;
                case 2: mShader.append("attribute vec2 ATTRIB_"); break;
                case 3: mShader.append("attribute vec3 ATTRIB_"); break;
                case 4: mShader.append("attribute vec4 ATTRIB_"); break;
                default:
                    rsAssert(0);
                }

                mShader.append(e->getFieldName(field));
                mShader.append(";\n");
            }
        }
        mShader.append(mUserShader);
    } else {
        mShader.append("attribute vec4 ATTRIB_LegacyPosition;\n");
        mShader.append("attribute vec4 ATTRIB_LegacyColor;\n");
        mShader.append("attribute vec3 ATTRIB_LegacyNormal;\n");
        mShader.append("attribute float ATTRIB_LegacyPointSize;\n");
        mShader.append("attribute vec4 ATTRIB_LegacyTexture;\n");

        for (uint32_t ct=0; ct < mUniformCount; ct++) {
            mShader.append("uniform mat4 ");
            mShader.append(mUniformNames[ct]);
            mShader.append(";\n");
        }

        mShader.append("void main() {\n");
        mShader.append("  gl_Position = UNI_MVP * ATTRIB_LegacyPosition;\n");
        mShader.append("  gl_PointSize = ATTRIB_LegacyPointSize;\n");

        mShader.append("  varColor = ATTRIB_LegacyColor;\n");
        if (mTextureMatrixEnable) {
            mShader.append("  varTex0 = UNI_TexMatrix * ATTRIB_LegacyTexture;\n");
        } else {
            mShader.append("  varTex0 = ATTRIB_LegacyTexture;\n");
        }
        //mShader.append("  pos.x = pos.x / 480.0;\n");
        //mShader.append("  pos.y = pos.y / 800.0;\n");
        //mShader.append("  gl_Position = pos;\n");
        mShader.append("}\n");
    }
}

void ProgramVertex::setupGL2(const Context *rsc, ProgramVertexState *state, ShaderCache *sc)
{
    //LOGE("sgl2 vtx1 %x", glGetError());
    if ((state->mLast.get() == this) && !mDirty) {
        //return;
    }

    rsc->checkError("ProgramVertex::setupGL2 start");
    glVertexAttrib4f(1, state->color[0], state->color[1], state->color[2], state->color[3]);

    const float *f = static_cast<const float *>(mConstants[0]->getPtr());

    Matrix mvp;
    mvp.load(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
    Matrix t;
    t.load(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET]);
    mvp.multiply(&t);

    glUniformMatrix4fv(sc->vtxUniformSlot(0), 1, GL_FALSE, mvp.m);
    if (mTextureMatrixEnable) {
        glUniformMatrix4fv(sc->vtxUniformSlot(1), 1, GL_FALSE,
                           &f[RS_PROGRAM_VERTEX_TEXTURE_OFFSET]);
    }

    rsc->checkError("ProgramVertex::setupGL2 begin uniforms");
    uint32_t uidx = 1;
    for (uint32_t ct=0; ct < mConstantCount; ct++) {
        Allocation *alloc = mConstants[ct+1].get();
        if (!alloc) {
            continue;
        }

        const uint8_t *data = static_cast<const uint8_t *>(alloc->getPtr());
        const Element *e = mConstantTypes[ct]->getElement();
        for (uint32_t field=0; field < e->getFieldCount(); field++) {
            const Element *f = e->getField(field);
            uint32_t offset = e->getFieldOffsetBytes(field);
            int32_t slot = sc->vtxUniformSlot(uidx);

            const float *fd = reinterpret_cast<const float *>(&data[offset]);

            //LOGE("Uniform  slot=%i, offset=%i, constant=%i, field=%i, uidx=%i", slot, offset, ct, field, uidx);
            if (slot >= 0) {
                switch(f->getComponent().getVectorSize()) {
                case 1:
                    //LOGE("Uniform 1 = %f", fd[0]);
                    glUniform1fv(slot, 1, fd);
                    break;
                case 2:
                    //LOGE("Uniform 2 = %f %f", fd[0], fd[1]);
                    glUniform2fv(slot, 1, fd);
                    break;
                case 3:
                    //LOGE("Uniform 3 = %f %f %f", fd[0], fd[1], fd[2]);
                    glUniform3fv(slot, 1, fd);
                    break;
                case 4:
                    //LOGE("Uniform 4 = %f %f %f %f", fd[0], fd[1], fd[2], fd[3]);
                    glUniform4fv(slot, 1, fd);
                    break;
                default:
                    rsAssert(0);
                }
            }
            uidx ++;
        }
    }

    for (uint32_t ct=0; ct < mConstantCount; ct++) {
        uint32_t glSlot = sc->vtxUniformSlot(ct + 1);

    }

    state->mLast.set(this);
    rsc->checkError("ProgramVertex::setupGL2");
}

void ProgramVertex::addLight(const Light *l)
{
    if (mLightCount < MAX_LIGHTS) {
        mLights[mLightCount].set(l);
        mLightCount++;
    }
}

void ProgramVertex::setProjectionMatrix(const rsc_Matrix *m) const
{
    float *f = static_cast<float *>(mConstants[0]->getPtr());
    memcpy(&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET], m, sizeof(rsc_Matrix));
    mDirty = true;
}

void ProgramVertex::setModelviewMatrix(const rsc_Matrix *m) const
{
    float *f = static_cast<float *>(mConstants[0]->getPtr());
    memcpy(&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET], m, sizeof(rsc_Matrix));
    mDirty = true;
}

void ProgramVertex::setTextureMatrix(const rsc_Matrix *m) const
{
    float *f = static_cast<float *>(mConstants[0]->getPtr());
    memcpy(&f[RS_PROGRAM_VERTEX_TEXTURE_OFFSET], m, sizeof(rsc_Matrix));
    mDirty = true;
}

void ProgramVertex::transformToScreen(const Context *rsc, float *v4out, const float *v3in) const
{
    float *f = static_cast<float *>(mConstants[0]->getPtr());
    Matrix mvp;
    mvp.loadMultiply((Matrix *)&f[RS_PROGRAM_VERTEX_MODELVIEW_OFFSET],
                     (Matrix *)&f[RS_PROGRAM_VERTEX_PROJECTION_OFFSET]);
    mvp.vectorMultiply(v4out, v3in);
}

void ProgramVertex::initAddUserElement(const Element *e, String8 *names, uint32_t *count, const char *prefix)
{
    rsAssert(e->getFieldCount());
    for (uint32_t ct=0; ct < e->getFieldCount(); ct++) {
        const Element *ce = e->getField(ct);
        if (ce->getFieldCount()) {
            initAddUserElement(ce, names, count, prefix);
        } else {
            String8 tmp(prefix);
            tmp.append(e->getFieldName(ct));
            names[*count].setTo(tmp.string());
            (*count)++;
        }
    }
}


void ProgramVertex::init(Context *rsc)
{
    mAttribCount = 0;
    if (mUserShader.size() > 0) {
        for (uint32_t ct=0; ct < mInputCount; ct++) {
            initAddUserElement(mInputElements[ct].get(), mAttribNames, &mAttribCount, "ATTRIB_");
        }

        mUniformCount = 1;
        mUniformNames[0].setTo("UNI_MVP");
        for (uint32_t ct=0; ct < mConstantCount; ct++) {
            initAddUserElement(mConstantTypes[ct]->getElement(), mUniformNames, &mUniformCount, "UNI_");
        }
    } else {
        mUniformCount = 2;
        mUniformNames[0].setTo("UNI_MVP");
        mUniformNames[1].setTo("UNI_TexMatrix");
    }

    createShader();
}


///////////////////////////////////////////////////////////////////////

ProgramVertexState::ProgramVertexState()
{
}

ProgramVertexState::~ProgramVertexState()
{
}

void ProgramVertexState::init(Context *rsc, int32_t w, int32_t h)
{
    RsElement e = Element::create(rsc, RS_TYPE_FLOAT_32, RS_KIND_USER, false, 1);

    rsi_TypeBegin(rsc, e);
    rsi_TypeAdd(rsc, RS_DIMENSION_X, 48);
    mAllocType.set((Type *)rsi_TypeCreate(rsc));

    ProgramVertex *pv = new ProgramVertex(rsc, false);
    Allocation *alloc = (Allocation *)rsi_AllocationCreateTyped(rsc, mAllocType.get());
    mDefaultAlloc.set(alloc);
    mDefault.set(pv);
    pv->init(rsc);
    pv->bindAllocation(alloc, 0);

    color[0] = 1.f;
    color[1] = 1.f;
    color[2] = 1.f;
    color[3] = 1.f;

    updateSize(rsc, w, h);
}

void ProgramVertexState::updateSize(Context *rsc, int32_t w, int32_t h)
{
    Matrix m;
    m.loadOrtho(0,w, h,0, -1,1);
    mDefaultAlloc->subData(RS_PROGRAM_VERTEX_PROJECTION_OFFSET, 16, &m.m[0], 16*4);

    m.loadIdentity();
    mDefaultAlloc->subData(RS_PROGRAM_VERTEX_MODELVIEW_OFFSET, 16, &m.m[0], 16*4);
}

void ProgramVertexState::deinit(Context *rsc)
{
    mDefaultAlloc.clear();
    mDefault.clear();
    mAllocType.clear();
    mLast.clear();
}


namespace android {
namespace renderscript {


RsProgramVertex rsi_ProgramVertexCreate(Context *rsc, bool texMat)
{
    ProgramVertex *pv = new ProgramVertex(rsc, texMat);
    pv->incUserRef();
    return pv;
}

RsProgramVertex rsi_ProgramVertexCreate2(Context *rsc, const char * shaderText,
                             uint32_t shaderLength, const uint32_t * params,
                             uint32_t paramLength)
{
    ProgramVertex *pv = new ProgramVertex(rsc, shaderText, shaderLength, params, paramLength);
    pv->incUserRef();
    return pv;
}


}
}
