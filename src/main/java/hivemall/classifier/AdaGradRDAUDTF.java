/*
 * Hivemall: Hive scalable Machine Learning Library
 *
 * Copyright (C) 2013
 *   National Institute of Advanced Industrial Science and Technology (AIST)
 *   Registration Number: H25PRO-1520
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package hivemall.classifier;

import hivemall.common.LossFunctions;
import hivemall.io.FeatureValue;
import hivemall.io.IWeightValue;
import hivemall.io.WeightValue.WeightValueParamsF2;
import hivemall.utils.lang.Primitives;

import java.util.List;

import javax.annotation.Nonnull;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;

public final class AdaGradRDAUDTF extends BinaryOnlineClassifierUDTF {

    private float eta;
    private float lambda;
    private float scaling;

    @Override
    public StructObjectInspector initialize(ObjectInspector[] argOIs) throws UDFArgumentException {
        final int numArgs = argOIs.length;
        if(numArgs != 2 && numArgs != 3) {
            throw new UDFArgumentException("AdaGradRDAUDTF takes 2 or 3 arguments: List<Text|Int|BitInt> features, int label [, constant string options]");
        }

        StructObjectInspector oi = super.initialize(argOIs);
        model.configureParams(true, false, true);
        return oi;
    }

    @Override
    protected Options getOptions() {
        Options opts = super.getOptions();
        opts.addOption("eta", "eta0", true, "The learning rate \\eta [default 0.1]");
        opts.addOption("lambda", true, "lambda constant of RDA [default: 1E-6f]");
        opts.addOption("scale", true, "Internal scaling/descaling factor for cumulative weights [default: 100]");
        return opts;
    }

    @Override
    protected CommandLine processOptions(ObjectInspector[] argOIs) throws UDFArgumentException {
        CommandLine cl = super.processOptions(argOIs);
        if(cl == null) {
            this.eta = 0.1f;
            this.lambda = 1E-6f;
            this.scaling = 100f;
        } else {
            this.eta = Primitives.parseFloat(cl.getOptionValue("eta"), 0.1f);
            this.lambda = Primitives.parseFloat(cl.getOptionValue("lambda"), 1E-6f);
            this.scaling = Primitives.parseFloat(cl.getOptionValue("scale"), 100f);
        }
        return cl;
    }

    @Override
    protected void train(final List<?> features, final int label) {
        final float y = label > 0 ? 1.f : -1.f;

        float p = predict(features);
        float loss = LossFunctions.hingeLoss(p, y); // 1.0 - y * p        
        if(loss <= 0.f) { // max(0, 1 - y * p)
            return;
        }
        // subgradient => -y * W dot xi
        update(features, y, count);
    }

    protected void update(final List<?> features, final float y, final int t) {
        final ObjectInspector featureInspector = featureListOI.getListElementObjectInspector();

        for(Object f : features) {// w[f] += y * x[f]
            if(f == null) {
                continue;
            }
            final Object x;
            final float xi;
            if(parseFeature) {
                FeatureValue fv = FeatureValue.parse(f);
                x = fv.getFeature();
                xi = fv.getValue();
            } else {
                x = ObjectInspectorUtils.copyToStandardObject(f, featureInspector);
                xi = 1.f;
            }

            updateWeight(x, xi, y, t);
        }
    }

    protected void updateWeight(@Nonnull final Object x, final float xi, final float y, final float t) {
        final float gradient = -y * xi;
        final float scaled_gradient = gradient * scaling;

        float scaled_sum_sqgrad = 0.f;
        float scaled_sum_grad = 0.f;
        IWeightValue old = model.get(x);
        if(old != null) {
            scaled_sum_sqgrad = old.getSumOfSquaredGradients();
            scaled_sum_grad = old.getSumOfGradients();
        }
        scaled_sum_grad += scaled_gradient;
        scaled_sum_sqgrad += (scaled_gradient * scaled_gradient);

        float sum_grad = scaled_sum_grad * scaling;
        double sum_sqgrad = scaled_sum_sqgrad * scaling;

        // sign(u_{t,i})
        float sign = (sum_grad > 0.f) ? 1.f : -1.f;
        // |u_{t,i}|/t - \lambda
        float meansOfGradients = sign * sum_grad / t - lambda;
        if(meansOfGradients < 0.f) {
            // x_{t,i} = 0
            model.delete(x);
        } else {
            // x_{t,i} = -sign(u_{t,i}) * \frac{\eta t}{\sqrt{G_{t,ii}}}(|u_{t,i}|/t - \lambda)
            float weight = -1.f * sign * eta * t * meansOfGradients / (float) Math.sqrt(sum_sqgrad);
            IWeightValue new_w = new WeightValueParamsF2(weight, scaled_sum_sqgrad, scaled_sum_grad);
            model.set(x, new_w);
        }
    }
}
