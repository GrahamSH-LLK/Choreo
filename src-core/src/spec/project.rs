use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use trajoptlib::Translation2d;

use super::{traj::DriveType, Expr, ExprOrNumber};

#[derive(Debug, Serialize, Deserialize, Clone, Copy)]
pub enum Dimension {
    Number,
    Length,
    LinVel,
    LinAcc,
    Angle,
    AngVel,
    AngAcc,
    Time,
    Mass,
    Torque,
    MoI,
}
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Variable {
    pub dimension: Dimension,
    pub var: Expr,
}
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PoseVariable {
    pub x: Expr,
    pub y: Expr,
    pub heading: Expr,
}
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Variables {
    pub expressions: HashMap<String, Variable>,
    pub poses: HashMap<String, PoseVariable>,
}
#[derive(Debug, Serialize, Deserialize, Clone, Copy)]
pub struct Bumper<T: ExprOrNumber> {
    pub front: T,
    pub left: T,
    pub back: T,
    pub right: T,
}

impl<T: ExprOrNumber> Bumper<T> {
    pub fn snapshot(&self) -> Bumper<f64> {
        Bumper {
            front: self.front.as_number(),
            left: self.left.as_number(),
            back: self.back.as_number(),
            right: self.right.as_number(),
        }
    }
}
#[derive(Debug, Serialize, Deserialize, Clone, Copy)]
pub struct Module<T: ExprOrNumber> {
    pub x: T,
    pub y: T,
}

impl<T: ExprOrNumber> Module<T> {
    pub fn snapshot(&self) -> Module<f64> {
        Module {
            x: self.x.as_number(),
            y: self.y.as_number(),
        }
    }
}
impl Module<f64> {
    #[must_use]
    pub fn translation(&self) -> Translation2d {
        Translation2d {
            x: self.x,
            y: self.y,
        }
    }
}
#[derive(Debug, Serialize, Deserialize, Clone, Copy)]
pub struct RobotConfig<T: ExprOrNumber> {
    pub modules: [Module<T>; 4],
    pub mass: T,
    pub inertia: T,
    pub gearing: T,
    pub radius: T,
    /// motor rad/s
    pub vmax: T, // motor rad/s
    /// motor N*m
    pub tmax: T, // N*m
    pub bumper: Bumper<T>,
}

impl<T: ExprOrNumber> RobotConfig<T> {
    pub fn snapshot(&self) -> RobotConfig<f64> {
        RobotConfig {
            modules: self.modules.clone().map(|modu: Module<T>| modu.snapshot()),
            mass: self.mass.as_number(),
            inertia: self.inertia.as_number(),
            gearing: self.gearing.as_number(),
            radius: self.radius.as_number(),
            vmax: self.vmax.as_number(),
            tmax: self.tmax.as_number(),
            bumper: self.bumper.snapshot(),
        }
    }
}
impl<T: ExprOrNumber> RobotConfig<T> {
    pub fn wheel_max_torque(&self) -> f64 {
        self.tmax.as_number() * self.gearing.as_number()
    }
    pub fn wheel_max_velocity(&self) -> f64 {
        self.vmax.as_number() / self.gearing.as_number()
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ProjectFile {
    pub name: String,
    pub version: String,
    #[serde(rename = "type", default)]
    pub r#type: DriveType,
    pub variables: Variables,
    pub config: RobotConfig<Expr>,
    #[serde(default)]
    pub generation_features: Vec<String>,
}

impl ProjectFile {
    pub const EXTENSION: &'static str = "chor";

    /// Create a new `ProjectFile` from a json string.
    ///
    /// # Errors
    /// - [`crate::ChoreoError::Json`] if the json string is invalid.
    pub fn from_content(content: &str) -> crate::ChoreoResult<ProjectFile> {
        serde_json::from_str(content).map_err(Into::into)
    }
}

impl Default for ProjectFile {
    fn default() -> Self {
        ProjectFile {
            name: "New Project".to_string(),
            version: "v2025.0.0".to_string(),
            r#type: DriveType::Swerve,
            variables: Variables {
                expressions: HashMap::new(),
                poses: HashMap::new(),
            },
            config: RobotConfig {
                gearing: Expr::new("6.5", 6.5),
                radius: Expr::new("2 in", 0.0508),
                vmax: Expr::new("6000.0 RPM", (6000.0 / 60.0) * std::f64::consts::TAU),
                tmax: Expr::new("1.2 N*m", 1.2),
                modules: [
                    Module {
                        x: Expr::new("11 in", 0.2794),
                        y: Expr::new("11 in", 0.2794),
                    },
                    Module {
                        x: Expr::new("-11 in", -0.2794),
                        y: Expr::new("11 in", 0.2794),
                    },
                    Module {
                        x: Expr::new("-11 in", -0.2794),
                        y: Expr::new("-11 in", -0.2794),
                    },
                    Module {
                        x: Expr::new("11 in", 0.2794),
                        y: Expr::new("-11 in", -0.2794),
                    },
                ],
                mass: Expr::new("150 lbs", 68.038_855_5),
                inertia: Expr::new("6 kg m^2", 6.0),
                bumper: Bumper {
                    front: Expr::new("16 in", 0.4064),
                    left: Expr::new("16 in", 0.4064),
                    back: Expr::new("16 in", 0.4064),
                    right: Expr::new("16 in", 0.4064),
                },
            },
            generation_features: Vec::new(),
        }
    }
}
