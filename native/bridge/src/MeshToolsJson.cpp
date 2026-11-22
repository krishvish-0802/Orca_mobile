// JSON for the mesh tools at the JNI boundary (see Json.hpp).
#include "orca_engine/Json.hpp"

#include <stdexcept>

#include "nlohmann/json.hpp"

namespace orca::engine::json {

CutRequest cut_request_from_json(const std::string& payload)
{
    try {
        const nlohmann::json j = nlohmann::json::parse(payload);
        CutRequest r;
        r.via.object = j.at("object").get<std::size_t>();
        r.via.instance = j.value("instance", std::size_t(0));
        if (j.contains("plane")) {
            const auto& p = j["plane"];
            r.plane.height = p.value("height", 0.0);
            r.plane.tilt_x_deg = p.value("tiltX", 0.0);
            r.plane.tilt_y_deg = p.value("tiltY", 0.0);
        }
        r.mode = static_cast<CutMode>(j.value("mode", 0));
        if (r.mode != CutMode::Planar && r.mode != CutMode::Dovetail)
            throw std::runtime_error("No such cut mode");
        if (j.contains("options")) {
            const auto& o = j["options"];
            r.options.keep_upper = o.value("keepUpper", true);
            r.options.keep_lower = o.value("keepLower", true);
            r.options.as_parts = o.value("asParts", false);
            r.options.place_on_cut_upper = o.value("placeOnCutUpper", false);
            r.options.place_on_cut_lower = o.value("placeOnCutLower", false);
            r.options.flip_upper = o.value("flipUpper", false);
            r.options.flip_lower = o.value("flipLower", false);
        }
        for (const auto& c : j.value("connectors", nlohmann::json::array())) {
            CutConnectorSpec spec;
            spec.at = {c.value("u", 0.0), c.value("v", 0.0)};
            const int type = c.value("type", 0), style = c.value("style", 0), shape = c.value("shape", 3);
            if (type < 0 || type > 2 || style < 0 || style > 1 || shape < 0 || shape > 3)
                throw std::runtime_error("No such connector");
            spec.type = static_cast<CutConnectorType>(type);
            spec.style = static_cast<CutConnectorStyle>(style);
            spec.shape = static_cast<CutConnectorShape>(shape);
            spec.size_mm = c.value("size", spec.size_mm);
            spec.depth_mm = c.value("depth", spec.depth_mm);
            spec.size_tolerance_mm = c.value("sizeTolerance", spec.size_tolerance_mm);
            spec.depth_tolerance_mm = c.value("depthTolerance", spec.depth_tolerance_mm);
            spec.rotation_deg = c.value("rotation", spec.rotation_deg);
            if (!(spec.size_mm > 0) || !(spec.depth_mm > 0))
                throw std::runtime_error("A connector needs a size and a depth");
            r.connectors.push_back(spec);
        }
        if (j.contains("dovetail")) {
            const auto& d = j["dovetail"];
            r.dovetail.depth_mm = d.value("depth", r.dovetail.depth_mm);
            r.dovetail.width_mm = d.value("width", r.dovetail.width_mm);
            r.dovetail.flaps_angle_deg = d.value("flapsAngle", r.dovetail.flaps_angle_deg);
            r.dovetail.angle_deg = d.value("angle", r.dovetail.angle_deg);
            r.dovetail.depth_tolerance_mm = d.value("depthTolerance", r.dovetail.depth_tolerance_mm);
            r.dovetail.width_tolerance_mm = d.value("widthTolerance", r.dovetail.width_tolerance_mm);
            r.dovetail.count = d.value("count", r.dovetail.count);
            r.dovetail.gap_mm = d.value("gap", r.dovetail.gap_mm);
        }
        return r;
    } catch (const nlohmann::json::exception& e) {
        throw std::runtime_error(std::string("Malformed cut: ") + e.what());
    }
}

std::string to_json(const CutPreview& p)
{
    nlohmann::json problems = nlohmann::json::array();
    for (ConnectorProblem problem : p.connector_problems)
        problems.push_back(static_cast<int>(problem));
    nlohmann::json j{
        {"boxMin", p.box_min},
        {"boxMax", p.box_max},
        {"center", p.center},
        {"normal", p.normal},
        {"axisX", p.axis_x},
        {"axisY", p.axis_y},
        {"problems", problems},
        {"canCut", p.can_cut},
        {"reason", p.reason},
        {"dovetailDepth", p.dovetail_depth_mm},
    };
    return j.dump();
}

SimplifyRequest simplify_request_from_json(std::size_t object, const std::string& payload)
{
    try {
        const nlohmann::json j = nlohmann::json::parse(payload);
        SimplifyRequest r;
        r.object = object;
        if (j.contains("volume") && !j["volume"].is_null())
            r.volume = j["volume"].get<std::size_t>();
        r.by_ratio = j.value("byRatio", false);
        r.ratio_percent = j.value("ratio", r.ratio_percent);
        r.detail = j.value("detail", r.detail);
        if (r.detail < 0 || r.detail > 4)
            throw std::runtime_error("No such detail level");
        return r;
    } catch (const nlohmann::json::exception& e) {
        throw std::runtime_error(std::string("Malformed simplify: ") + e.what());
    }
}

std::string to_json(const SimplifyOutcome& outcome)
{
    return nlohmann::json{{"before", outcome.before}, {"after", outcome.after}}.dump();
}

namespace {

FeaturePick pick_of(const nlohmann::json& j)
{
    FeaturePick p;
    p.via.object = j.at("object").get<std::size_t>();
    p.via.instance = j.value("instance", std::size_t(0));
    p.volume = j.at("volume").get<std::size_t>();
    p.facet = j.at("facet").get<int>();
    p.hit = j.at("hit").get<std::array<double, 3>>();
    return p;
}

FeatureFilter filter_of(const nlohmann::json& j)
{
    const int filter = j.value("filter", 0);
    if (filter < 0 || filter > 2)
        throw std::runtime_error("No such feature filter");
    return static_cast<FeatureFilter>(filter);
}

} // namespace

std::vector<FeaturePick> feature_picks_from_json(const std::string& payload, FeatureFilter& filter)
{
    try {
        const nlohmann::json j = nlohmann::json::parse(payload);
        filter = filter_of(j);
        std::vector<FeaturePick> picks;
        for (const auto& p : j.value("picks", nlohmann::json::array()))
            picks.push_back(pick_of(p));
        return picks;
    } catch (const nlohmann::json::exception& e) {
        throw std::runtime_error(std::string("Malformed measure: ") + e.what());
    }
}

AssembleRequest assemble_request_from_json(const std::string& payload)
{
    try {
        const nlohmann::json j = nlohmann::json::parse(payload);
        AssembleRequest r;
        r.first = pick_of(j.at("first"));
        r.second = pick_of(j.at("second"));
        r.filter = filter_of(j);
        const int step = j.at("step").get<int>();
        if (step < 0 || step > 6)
            throw std::runtime_error("No such assembly step");
        r.step = static_cast<AssemblyStep>(step);
        r.value = j.value("value", 0.0);
        if (j.contains("xyz"))
            r.xyz = j["xyz"].get<std::array<double, 3>>();
        return r;
    } catch (const nlohmann::json::exception& e) {
        throw std::runtime_error(std::string("Malformed assembly: ") + e.what());
    }
}

std::string to_json(const MeasureOutcome& outcome)
{
    nlohmann::json features = nlohmann::json::array();
    for (const MeasuredFeature& f : outcome.features)
        features.push_back({{"kind", static_cast<int>(f.kind)}, {"a", f.a}, {"b", f.b}, {"radius", f.radius}});
    nlohmann::json j{{"features", features}};
    if (outcome.measurement) {
        const Measurement& m = *outcome.measurement;
        nlohmann::json mj = nlohmann::json::object();
        if (m.angle_deg)
            mj["angle"] = *m.angle_deg;
        if (m.distance) {
            mj["distance"] = *m.distance;
            mj["from"] = m.from;
            mj["to"] = m.to;
        }
        if (m.distance_infinite)
            mj["distanceInfinite"] = *m.distance_infinite;
        if (m.xyz)
            mj["xyz"] = *m.xyz;
        j["measurement"] = mj;
    }
    const AssemblyOptions& a = outcome.assembly;
    j["assembly"] = {
        {"differentParts", a.different_parts},
        {"sameObject", a.same_object},
        {"parallel", a.parallel},
        {"centerCoincidence", a.center_coincidence},
        {"flipFirst", a.flip_first},
        {"flipSecond", a.flip_second},
        {"rotateAround", a.rotate_around},
        {"hasParallelDistance", a.has_parallel_distance},
        {"parallelDistance", a.parallel_distance},
        {"angle", a.angle_deg},
        {"setXyz", a.set_xyz},
    };
    return j.dump();
}

} // namespace orca::engine::json
