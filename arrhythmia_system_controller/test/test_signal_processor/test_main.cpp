// Host unit tests for SignalProcessor: pio test -e native
//
// Synthetic ECG (Gaussian P/QRS/T waves in ADC counts) with known R-peak
// times, so detection, beat windows and RR features can be checked against
// ground truth without MIT-BIH data. The RR assertions pin the contract with
// preprocess.py::compute_rr_features — change one, change both.
#include <unity.h>
#include <cmath>
#include <cstdlib>
#include <vector>
#include "signal_processing/SignalProcessor.h"

namespace {

constexpr int FS = 250;

struct Beat {
    long r;          // R-peak sample index reported by the processor
    float ecg[SamplingCfg::BEAT_WINDOW_SAMPLES];
    float rr[MLCfg::N_RR_FEATURES];
};

// One P-QRS-T complex centred on each R time (samples).
std::vector<int16_t> synthEcg(const std::vector<long>& rTimes, long length) {
    std::vector<int16_t> x(length);
    auto g = [](double t, double mu, double sigma, double amp) {
        const double d = (t - mu) / sigma;
        return amp * std::exp(-0.5 * d * d);
    };
    for (long n = 0; n < length; ++n) {
        double v = 0.0;
        for (long r : rTimes) {
            if (std::labs(n - r) > 150) continue;
            v += g(n, r - 45, 6.0, 120.0);    // P
            v += g(n, r - 6, 2.0, -150.0);    // Q
            v += g(n, r, 2.5, 1200.0);        // R
            v += g(n, r + 6, 2.0, -250.0);    // S
            v += g(n, r + 70, 12.0, 300.0);   // T
        }
        x[n] = static_cast<int16_t>(std::lround(v));
    }
    return x;
}

std::vector<Beat> run(SignalProcessor& p, const std::vector<int16_t>& x) {
    std::vector<Beat> out;
    for (long n = 0; n < static_cast<long>(x.size()); ++n) {
        p.processSample(x[n]);
        if (p.isBeatReady()) {
            Beat b;
            b.r = n - static_cast<long>(p.samplesSinceR());
            p.getBeat(b.ecg);
            p.getRRFeatures(b.rr);
            out.push_back(b);
        }
    }
    return out;
}

std::vector<long> regularRhythm(long first, long rr, int count) {
    std::vector<long> r;
    for (int i = 0; i < count; ++i) r.push_back(first + i * rr);
    return r;
}

// Index of the true R time nearest to `r`, or -1 if none within 5 samples.
int matchTruth(const std::vector<long>& truth, long r) {
    for (size_t i = 0; i < truth.size(); ++i)
        if (std::labs(truth[i] - r) <= 5) return static_cast<int>(i);
    return -1;
}

}  // namespace

void test_every_beat_detected_except_first_and_last() {
    const auto truth = regularRhythm(FS, FS, 30);   // 60 bpm, 30 beats
    SignalProcessor p;
    const auto beats = run(p, synthEcg(truth, truth.back() + 2 * FS));

    // First R has no predecessor (no pre_rr); last has no successor (held).
    TEST_ASSERT_EQUAL(static_cast<int>(truth.size()) - 2, static_cast<int>(beats.size()));
    for (size_t i = 0; i < beats.size(); ++i) {
        TEST_ASSERT_EQUAL(static_cast<int>(i) + 1, matchTruth(truth, beats[i].r));
    }
}

void test_window_is_centred_96_pre_32_post() {
    const auto truth = regularRhythm(FS, 200, 12);
    SignalProcessor p;
    const auto beats = run(p, synthEcg(truth, truth.back() + 2 * FS));
    TEST_ASSERT_TRUE(beats.size() > 5);

    for (const auto& b : beats) {
        size_t peak = 0;
        for (size_t i = 1; i < SamplingCfg::BEAT_WINDOW_SAMPLES; ++i)
            if (std::fabs(b.ecg[i]) > std::fabs(b.ecg[peak])) peak = i;
        TEST_ASSERT_EQUAL(SamplingCfg::BEAT_PRE_SAMPLES, peak);
    }
}

void test_post_rr_is_the_real_next_interval() {
    // Sinus at 250 samples, then a premature beat (pre 140) followed by a
    // compensatory pause (post 360), then sinus again.
    std::vector<long> truth = regularRhythm(FS, 250, 9);
    const long ectopic = truth.back() + 140;
    truth.push_back(ectopic);
    truth.push_back(ectopic + 360);
    for (int i = 0; i < 4; ++i) truth.push_back(truth.back() + 250);
    SignalProcessor p;
    const auto beats = run(p, synthEcg(truth, truth.back() + 2 * FS));

    bool found = false;
    for (const auto& b : beats) {
        if (std::labs(b.r - ectopic) > 5) continue;
        found = true;
        TEST_ASSERT_LESS_THAN_FLOAT(0.7f, b.rr[0]);      // premature
        TEST_ASSERT_GREATER_THAN_FLOAT(1.3f, b.rr[1]);   // compensatory pause
        TEST_ASSERT_FLOAT_WITHIN(1e-5f, b.rr[0] / b.rr[1], b.rr[2]);
        TEST_ASSERT_LESS_THAN_FLOAT(0.5f, b.rr[2]);      // was ~1 when post_rr = pre_rr
    }
    TEST_ASSERT_TRUE_MESSAGE(found, "ectopic beat not emitted");
}

void test_rr_mean_is_last_8_intervals_up_to_pre_rr() {
    // Steadily changing rate so the running-mean window length matters.
    std::vector<long> truth{FS};
    for (int i = 0; i < 20; ++i) truth.push_back(truth.back() + 180 + 6 * i);
    SignalProcessor p;
    const auto beats = run(p, synthEcg(truth, truth.back() + 2 * FS));
    TEST_ASSERT_TRUE(beats.size() > 12);

    // Reconstruct from the processor's own R positions (consecutive beats).
    std::vector<long> r;
    for (const auto& b : beats) r.push_back(b.r);
    for (size_t i = 9; i + 1 < r.size(); ++i) {
        double sum = 0.0;
        for (size_t k = i - 8; k < i; ++k) sum += static_cast<double>(r[k + 1] - r[k]);
        const double mean = sum / 8.0;
        const double pre  = static_cast<double>(r[i] - r[i - 1]);
        const double post = static_cast<double>(r[i + 1] - r[i]);
        TEST_ASSERT_FLOAT_WITHIN(1e-4f, static_cast<float>(pre / mean),  beats[i].rr[0]);
        TEST_ASSERT_FLOAT_WITHIN(1e-4f, static_cast<float>(post / mean), beats[i].rr[1]);
    }
}

void test_reset_drops_state_and_the_first_beat() {
    const auto truth = regularRhythm(FS, FS, 12);
    const auto x = synthEcg(truth, truth.back() + 2 * FS);
    SignalProcessor p;
    run(p, x);
    p.reset();
    const auto beats = run(p, x);
    TEST_ASSERT_EQUAL(static_cast<int>(truth.size()) - 2, static_cast<int>(beats.size()));
    TEST_ASSERT_EQUAL(1, matchTruth(truth, beats.front().r));
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_every_beat_detected_except_first_and_last);
    RUN_TEST(test_window_is_centred_96_pre_32_post);
    RUN_TEST(test_post_rr_is_the_real_next_interval);
    RUN_TEST(test_rr_mean_is_last_8_intervals_up_to_pre_rr);
    RUN_TEST(test_reset_drops_state_and_the_first_beat);
    return UNITY_END();
}
