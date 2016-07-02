/**
 *
 */
angular.module('verewaConfigView', [])
.controller('verewaConfigCtrl',
[ 'InstConfigService',
  '$scope',
  function($config, $scope) {
	function handleConfig(config){
		$scope.param = config.param;
		//console.log(config);
		config.summary = function() {
			var desc = "測項:" + $scope.param.monitorType;

			return desc;
		}

		config.validate=function(){
			//copy back
			config.param = $scope.param;
			return true;
		}		
	}
	handleConfig($config);
	$config.subscribeConfigChanged($scope, function(event, config){
		handleConfig(config);
		});
  } ]);
