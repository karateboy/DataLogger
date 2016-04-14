/**
 * 
 */
angular.module('baselineConfigView', [])
.controller('baselineConfigCtrl',
[ 'InstConfigService', 
  '$scope', 
  function($config, $scope) {
	$scope.param = $config.param;

	if(isEmpty($scope.param)){
		$scope.param={
				calibrationTimeDate: new Date(1970, 0, 1, 15, 0, 0),
				raiseTime:300,
				holdTime:60,
				downTime:300
			};
	}else{
		//angular require calibrationTime to be Date		
		$scope.param.calibrationTimeDate = moment($scope.param.calibrationTime, "HH:mm:ss").toDate(); 
	}
	
	$config.summary = function() {
		var desc = "";
		{
			if($scope.param.calibrationTimeDate instanceof Date)
				desc += "<br/>校正時間:" + $scope.param.calibrationTimeDate.toLocaleTimeString();
			
			desc += "<br/>校正上升時間:" + $scope.param.raiseTime;
			desc += "<br/>校正持續時間:" + $scope.param.holdTime;
			desc += "<br/>校正下降時間:" + $scope.param.downTime;
		}
		
		return desc;
	}

	$config.validate=function(){
		
		if(!$scope.param.calibrationTimeDate){
			alert("沒有設定校正時間!");
			return false;
		}

		if(!$scope.param.raiseTime){
			alert("沒有設定校正上升時間!");
			return false;
		}

		if(!$scope.param.holdTime){
			alert("沒有設定校正持續時間!");
			return false;
		}
		
		if(!$scope.param.downTime){
			alert("沒有設定校正下降時間!");
			return false;
		}
		
		$scope.param.calibrationTime = $scope.param.calibrationTimeDate.getTime();
		$scope.param.raiseTime = parseInt($scope.param.raiseTime);
		$scope.param.holdTime = parseInt($scope.param.holdTime);
		$scope.param.raiseTime = parseInt($scope.param.raiseTime);
		
		//copy back
		$config.param = $scope.param;
		
		return true;
	}
  } ]);